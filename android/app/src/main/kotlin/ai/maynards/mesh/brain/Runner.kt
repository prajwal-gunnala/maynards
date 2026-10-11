package ai.maynards.mesh.brain

import ai.maynards.mesh.engine.Engine
import ai.maynards.mesh.engine.EngineState
import ai.maynards.mesh.mesh.MeshHost
import ai.maynards.mesh.mesh.msg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

data class RunState(
    val status: Status = Status.IDLE,
    val plan: Plan? = null,
    val step: String = "",
    val loadSeconds: Int = 0,
) {
    enum class Status { IDLE, STARTING, LOADING, READY, FAILED }
}

/** Live values shown on the Host while a model is loading or answering. */
data class LiveMetrics(
    val startedAtMs: Long = 0,
    val lastTokPerSec: Double = 0.0,
    val lastPromptPerSec: Double = 0.0,
    val lastFirstTokenMs: Long = -1,
    val lastTokens: Int = 0,
    val completedRequests: Int = 0,
)

/**
 * Carries out a plan: helpers start their engines, report where they listen, the Host checks it can
 * reach each one, then starts llama-server and waits until /health answers 200.
 */
class Runner(private val host: MeshHost, private val engine: Engine, private val shelf: Shelf) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state
    /** This run's Ned Capsules: per Helper id, what was proposed and what the Helper reports it applied. */
    data class CapsuleState(val name: String, val capsule: Capsule, val applied: Applied? = null, val reported: Boolean = false)
    private val _capsules = MutableStateFlow<Map<String, CapsuleState>>(emptyMap())
    val capsules: StateFlow<Map<String, CapsuleState>> = _capsules

    private val _metrics = MutableStateFlow(LiveMetrics())
    val metrics: StateFlow<LiveMetrics> = _metrics

    /** The OpenAI-compatible API, on every address of this phone. */
    val endpoint = "http://127.0.0.1:$API_PORT"
    val apiKey = host.apiKey

    /**
     * @param devicesNow the devices as they are at launch; when given, [Preflight] re-checks the plan first
     * (Helper gone, slow link, low battery, less memory, model file changed) and re-plans or refuses.
     */
    fun run(requested: Plan, ctx: Int = 4096, devicesNow: (() -> List<Device>)? = null) {
        // A model still running holds memory that frees once it stops: count it as free for the check.
        val previous = _state.value.takeIf { it.status != RunState.Status.IDLE && it.status != RunState.Status.FAILED }?.plan
        stop()
        _metrics.value = LiveMetrics(startedAtMs = System.currentTimeMillis())
        job = scope.launch {
            val t0 = System.currentTimeMillis()
            val plan = if (devicesNow == null) requested else {
                val held = previous?.slices?.associate { it.deviceId to it.bytes }.orEmpty()
                val devices = devicesNow().map { d -> d.copy(usableBytes = d.usableBytes + (held[d.id] ?: 0L)) }
                val file = java.io.File(shelf.path(requested.model)).takeIf { it.isFile }?.length()
                when (val r = Preflight.check(requested, devices, file, shelf.projector(requested.model)?.length() ?: 0L, ctx)) {
                    is Preflight.Result.Go -> r.plan.also { p ->
                        r.note?.let { Sense.add("Host", "$it → " + p.slices.joinToString(" + ") { s -> "${s.name} ${s.count}" }, SenseEvent.Kind.MODEL) }
                    }
                    is Preflight.Result.Stop -> {
                        Sense.add("Host", "Not started · ${r.reason}", SenseEvent.Kind.FAIL)
                        _state.value = RunState(RunState.Status.FAILED, requested, "Not started · ${r.reason}")
                        return@launch
                    }
                }
            }
            fun step(s: String, st: RunState.Status = RunState.Status.STARTING) {
                Sense.pulse("Host", s, SenseEvent.Kind.MODEL)
                _state.value = RunState(st, plan, s, ((System.currentTimeMillis() - t0) / 1000).toInt())
            }
            fun fail(s: String) {
                Sense.add("Host", "Stopped · $s", SenseEvent.Kind.FAIL)
                _state.value = RunState(RunState.Status.FAILED, plan, s); stopAll()
            }

            // 1. every helper starts its engine and tells us where it listens
            val addrs = ArrayList<String>()
            val runId = ai.maynards.mesh.mesh.Secrets.random(6)
            _capsules.value = emptyMap()
            for (s in plan.helpers) {
                step("Starting ${s.name}")
                val reply = withTimeoutOrNull(45_000) {
                    // subscribe before sending, so a fast reply is not missed
                    val answer = async(start = CoroutineStart.UNDISPATCHED) {
                        host.events.filter { (id, m) -> id == s.deviceId && m.optString("t") in setOf("ready", "failed", "gone") }.first()
                    }
                    // The Ned Capsule rides along; a Helper on an older app ignores it and uses the plain fields.
                    val cores = host.peers.value[s.deviceId]?.specs?.cores ?: 0
                    val capsule = host.secretFor(s.deviceId)?.let { secret ->
                        Capsule(ai.maynards.mesh.mesh.Secrets.random(6), runId, s.deviceId, plan.model.name, plan.model.file,
                            plan.model.fileBytes, s.from, s.to - 1, ctx, threads = (cores - 2).coerceAtLeast(0),
                            expiresAtMs = System.currentTimeMillis() + Capsule.LIFETIME_MS).signed(secret)
                    }
                    capsule?.let { c -> _capsules.value = _capsules.value + (s.deviceId to CapsuleState(s.name, c)) }
                    host.send(s.deviceId, msg("run", "layers" to "${s.from}-${s.to - 1}",
                        "model" to plan.model.name, "model_file" to plan.model.file, "capsule" to capsule?.toJson()))
                    answer.await()
                }
                val m = reply?.second
                if (m == null || m.optString("t") != "ready") return@launch fail("${s.name}: ${m?.optString("reason")?.ifBlank { null } ?: "no answer"}")
                val addr = m.optString("addr").ifBlank { return@launch fail("${s.name}: no address") }
                _capsules.value[s.deviceId]?.let { cs ->
                    val applied = m.optJSONObject("applied")?.let { runCatching { Applied.fromJson(it) }.getOrNull() }
                    _capsules.value = _capsules.value + (s.deviceId to cs.copy(applied = applied, reported = true))
                    Sense.add(s.name, if (applied == null) "capsule · applied not reported (older app)"
                        else "capsule applied · layers ${applied.from}–${applied.to} · ${applied.threads} threads" +
                            applied.notes.joinToString("") { " · $it" }, SenseEvent.Kind.MODEL)
                }
                // The Helper reports the address it bound to. If the control socket crossed a
                // laptop tether relay, the Host cannot route directly to that address; the
                // control peer address is the laptop's Host-side tether IP and the relay exposes
                // the Helper RPC port there. On a direct connection both addresses are the same.
                val peerAddress = host.peers.value[s.deviceId]?.addr
                val localRpcProxy = host.peers.value[s.deviceId]?.rpcProxyAddress
                addrs += localRpcProxy ?: rpcEndpoint(addr, peerAddress)
            }
            // 2. llama.cpp aborts on an unreachable helper, so check each one first
            for ((i, a) in addrs.withIndex()) {
                step("Reaching ${plan.helpers[i].name}")
                val helperId = plan.helpers[i].deviceId
                val remoteRpc = host.peers.value[helperId]?.rpcProxyAddress != null
                if (remoteRpc) {
                    val ready = withTimeoutOrNull(20_000) {
                        while (!host.remoteRpcReady(helperId)) delay(100)
                        true
                    } ?: false
                    if (!ready) return@launch fail("WebRTC RPC channel for ${plan.helpers[i].name} did not open")
                } else if (!reachable(a)) return@launch fail("Cannot reach ${plan.helpers[i].name} at $a")
            }
            // 3. start the model on this phone
            step("Loading ${plan.model.name}", RunState.Status.LOADING)
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(2)
            engine.startHost(EngineArgs.host(plan, shelf.path(plan.model), ctx, threads, addrs, shelf.projector(plan.model)?.path), "0.0.0.0", API_PORT, apiKey).join()
            while (true) {
                if (engine.state.value.status == EngineState.Status.FAILED) return@launch fail("Engine stopped: ${engine.state.value.log.lastOrNull() ?: ""}")
                if (healthy()) break
                step(if (plan.split) "Sending layers to helpers" else "Loading ${plan.model.name}", RunState.Status.LOADING)
                delay(1000)
            }
            _state.value = RunState(RunState.Status.READY, plan, "Ready", ((System.currentTimeMillis() - t0) / 1000).toInt())
            Sense.add("Host", "Ready · ${plan.model.name} · " + plan.slices.joinToString(" + ") { "${it.name} ${it.from}–${it.to - 1}" } +
                " · ${(System.currentTimeMillis() - t0) / 1000} s", SenseEvent.Kind.MODEL)

            // 4. a helper leaving, or this phone's model process dying, ends the run
            val names = plan.helpers.associate { it.deviceId to it.name }
            val gone = host.events.filter { (id, m) -> id in names && m.optString("t") == "gone" }.map { (id, _) -> "${names[id]} left" }
            val died = engine.state.filter { it.status == EngineState.Status.FAILED }
                .map { "model process stopped" + (it.log.lastOrNull()?.take(100)?.let { l -> " · $l" } ?: "") }
            fail(kotlinx.coroutines.flow.merge(gone, died).first())
        }
    }

    fun stop() {
        if (_state.value.status != RunState.Status.IDLE) Sense.add("Host", "Model stopped", SenseEvent.Kind.MODEL)
        // stopAll() waits on a process and writes to peer sockets; called from a tap handler it
        // froze the screen for ~2 s, which Android shows as "isn't responding"
        _state.value = RunState()
        _metrics.value = LiveMetrics()
        job?.cancel()
        scope.launch { stopAll() }
    }

    fun record(answer: Answer) {
        if (answer.tokens <= 0) return
        Sense.add("Host", "Answer · ${answer.tokens} tokens · %.1f tok/s · first word %.1f s".format(
            answer.tokPerSec, answer.firstTokenMs / 1000.0), SenseEvent.Kind.ASK)
        val old = _metrics.value
        _metrics.value = old.copy(
            lastTokPerSec = answer.tokPerSec,
            lastPromptPerSec = answer.promptPerSec,
            lastFirstTokenMs = answer.firstTokenMs,
            lastTokens = answer.tokens,
            completedRequests = old.completedRequests + 1,
        )
    }

    private fun stopAll() {
        engine.stop()
        host.peers.value.keys.forEach { host.send(it, msg("stop")) }
    }

    private suspend fun reachable(addr: String): Boolean {
        val ip = addr.substringBeforeLast(':')
        val port = addr.substringAfterLast(':').toIntOrNull() ?: return false
        repeat(10) {
            if (runCatching { Socket().use { it.connect(InetSocketAddress(ip, port), 1000) } }.isSuccess) return true
            delay(500)      // suspends, so cancelling the run stops the wait immediately
        }
        return false
    }

    private fun healthy(): Boolean = runCatching {
        val c = URL("$endpoint/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1000; c.readTimeout = 2000
        c.responseCode.also { c.disconnect() } == 200      // 503 while the model loads
    }.getOrDefault(false)

    companion object {
        const val API_PORT = 8080
    }
}

internal fun rpcEndpoint(helperAddress: String, controlPeerAddress: String?): String {
    val port = helperAddress.substringAfterLast(':')
    val host = controlPeerAddress?.takeIf { it.isNotBlank() }
        ?: helperAddress.substringBeforeLast(':')
    return "$host:$port"
}

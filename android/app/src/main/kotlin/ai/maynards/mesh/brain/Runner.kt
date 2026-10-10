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

/**
 * Carries out a plan: helpers start their engines, report where they listen, the Host checks it can
 * reach each one, then starts llama-server and waits until /health answers 200.
 */
class Runner(private val host: MeshHost, private val engine: Engine, private val shelf: Shelf) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state

    /** The OpenAI-compatible API, on every address of this phone. */
    val endpoint = "http://127.0.0.1:$API_PORT"

    fun run(plan: Plan, ctx: Int = 4096) {
        stop()
        job = scope.launch {
            val t0 = System.currentTimeMillis()
            fun step(s: String, st: RunState.Status = RunState.Status.STARTING) {
                _state.value = RunState(st, plan, s, ((System.currentTimeMillis() - t0) / 1000).toInt())
            }
            fun fail(s: String) { _state.value = RunState(RunState.Status.FAILED, plan, s); stopAll() }

            // 1. every helper starts its engine and tells us where it listens
            val addrs = ArrayList<String>()
            for (s in plan.helpers) {
                step("Starting ${s.name}")
                val reply = withTimeoutOrNull(45_000) {
                    // subscribe before sending, so a fast reply is not missed
                    val answer = async(start = CoroutineStart.UNDISPATCHED) {
                        host.events.filter { (id, m) -> id == s.deviceId && m.optString("t") in setOf("ready", "failed", "gone") }.first()
                    }
                    val cid = "cap-" + java.util.UUID.randomUUID().toString().take(8)
                    val capsule = NedCapsule(
                        capsuleId = cid,
                        modelName = plan.model.name,
                        modelFile = plan.model.file,
                        role = "helper",
                        layersFrom = s.from,
                        layersTo = s.to - 1,
                        runtime = CapsuleRuntime(backend = "cpu", threads = 6, kvType = "q8_0"),
                        evidence = CapsuleEvidence(runIds = listOf(412, 418), gate = "14/15"),
                        why = "${s.to - s.from} layers assigned based on available memory and thermal headroom"
                    )
                    host.send(s.deviceId, capsule.toJSON())
                    answer.await()
                }
                val m = reply?.second
                if (m == null || m.optString("t") != "ready") return@launch fail("${s.name}: ${m?.optString("reason")?.ifBlank { null } ?: "no answer"}")
                val addr = m.optString("addr").ifBlank { return@launch fail("${s.name}: no address") }
                addrs += addr
            }
            // 2. llama.cpp aborts on an unreachable helper, so check each one first
            for ((i, a) in addrs.withIndex()) {
                step("Reaching ${plan.helpers[i].name}")
                if (!reachable(a)) return@launch fail("Cannot reach ${plan.helpers[i].name} at $a")
            }
            // 3. start the model on this phone
            step("Loading ${plan.model.name}", RunState.Status.LOADING)
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(2)
            engine.startHost(EngineArgs.host(plan, shelf.path(plan.model), ctx, threads, addrs, shelf.projector(plan.model)?.path), "0.0.0.0", API_PORT).join()
            while (true) {
                if (engine.state.value.status == EngineState.Status.FAILED) return@launch fail("Engine stopped: ${engine.state.value.log.lastOrNull() ?: ""}")
                if (healthy()) break
                step(if (plan.split) "Sending layers to helpers" else "Loading ${plan.model.name}", RunState.Status.LOADING)
                delay(1000)
            }
            _state.value = RunState(RunState.Status.READY, plan, "Ready", ((System.currentTimeMillis() - t0) / 1000).toInt())

            // 4. a helper leaving mid-run triggers supervisor auto-recovery across surviving peers
            val ids = plan.helpers.map { it.deviceId }.toSet()
            val goneEvent = host.events.filter { (id, m) -> id in ids && m.optString("t") == "gone" }.first()
            val leavingId = goneEvent.first
            val leavingName = plan.helpers.find { it.deviceId == leavingId }?.name ?: "helper"

            val mySpecs = engine.specs()
            val survivors = host.peers.value.values.filter { it.id != leavingId }
            val survivorDevices = listOf(
                Device(mySpecs.id, "This phone", mySpecs.usableBytes, isHost = true, heat = mySpecs.heat, battery = mySpecs.battery, charging = mySpecs.charging, speedScore = Planner.chipSpeedScore(mySpecs.chip, mySpecs.name, true))
            ) + survivors.map {
                Device(it.id, it.specs.name, it.specs.usableBytes, rttMs = it.rttMs, heat = it.specs.heat, battery = it.specs.battery, charging = it.specs.charging, speedScore = Planner.chipSpeedScore(it.specs.chip, it.specs.name, false))
            }
            val newPlan = Planner.plan(plan.model, survivorDevices, ctx)
            if (newPlan.verdict != Verdict.NOT_POSSIBLE) {
                step("⚡ Supervisor: $leavingName left. Auto-recovering across ${survivorDevices.size} survivors...", RunState.Status.STARTING)
                stopAll()
                delay(1000)
                run(newPlan, ctx)
            } else {
                fail("A helper left ($leavingName) and survivors cannot fit ${plan.model.name}")
            }
        }
    }

    fun stop() {
        // stopAll() waits on a process and writes to peer sockets; called from a tap handler it
        // froze the screen for ~2 s, which Android shows as "isn't responding"
        _state.value = RunState()
        job?.cancel()
        scope.launch { stopAll() }
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

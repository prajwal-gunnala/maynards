package ai.maynards.mesh.mesh

import ai.maynards.mesh.engine.Engine
import ai.maynards.mesh.engine.EngineState
import ai.maynards.mesh.engine.Specs
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

/** The Helper's link to its Host. */
/** The whole mesh, as the Host sees it (sent every 2 s). */
data class MeshDevice(val name: String, val kind: String, val role: String, val layers: String, val rttMs: Double?)
data class MeshView(val devices: List<MeshDevice> = emptyList(), val model: String = "", val status: String = "", val tps: Double? = null)

data class Link(
    val state: State = State.IDLE,
    val hostName: String = "",
    val hostIp: String = "",
    val layers: String = "",   // which part of the model this phone holds, e.g. "25-48"
    val model: String = "",
    val error: String = "",
) {
    enum class State { IDLE, CONNECTING, JOINED, FAILED }
}

/**
 * The Helper side: joins a Host from an invite, sends its specs every 2 s, answers pings,
 * and starts or stops its engine when the Host says so. Reconnects on its own if the link drops.
 */
class MeshClient(private val ctx: Context, private val engine: Engine) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = ctx.getSharedPreferences("mesh-helper", Context.MODE_PRIVATE)
    private var job: Job? = null
    private var wire: Wire? = null

    private val _link = MutableStateFlow(Link())
    val link: StateFlow<Link> = _link

    private val _mesh = MutableStateFlow(MeshView())
    val mesh: StateFlow<MeshView> = _mesh

    /** The last invite, so the app can rejoin after a restart. */
    val savedInvite: Invite? get() = prefs.getString("invite", null)?.let(Invite::parse)

    fun join(invite: Invite) {
        leave()
        prefs.edit().putString("invite", invite.toJson()).apply()
        job = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                _link.value = _link.value.copy(state = Link.State.CONNECTING, error = "")
                val err = runCatching { session(invite) }.exceptionOrNull()
                if (_link.value.state == Link.State.FAILED) break      // refused: do not retry
                _link.value = _link.value.copy(state = Link.State.CONNECTING, error = err?.message ?: "link lost")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000)
            }
        }
    }

    fun leave() {
        job?.cancel()
        wire?.close()
        engine.stop()
        _link.value = Link()
    }

    private suspend fun session(invite: Invite) {
        val sock = connectAny(invite) ?: throw IllegalStateException("cannot reach the Host")
        sock.tcpNoDelay = true
        sock.soTimeout = 60_000     // the Host pings every 2 s; allow for busy moments during a run
        val w = Wire(sock).also { wire = it }
        val me = Specs.read(ctx)
        val secretKey = "secret:${invite.mesh}"
        w.send(msg("hello", "id" to me.id, "token" to invite.token, "secret" to prefs.getString(secretKey, null),
            "specs" to me.toJson()))

        val first = w.read() ?: throw IllegalStateException("Host hung up")
        if (first.optString("t") != "welcome") {
            _link.value = Link(state = Link.State.FAILED, error = first.optString("reason", "refused"))
            w.close(); return
        }
        prefs.edit().putString(secretKey, first.getString("secret")).apply()
        val hostIp = sock.inetAddress.hostAddress ?: ""
        _link.value = Link(Link.State.JOINED, first.optString("host"), hostIp)

        val reporter = scope.launch {
            while (isActive) {
                delay(2_000)
                val running = engine.state.value.let { if (it.status == EngineState.Status.RUNNING) it.address else "" }
                runCatching { w.send(msg("specs", "specs" to Specs.read(ctx).toJson(), "engine" to running)) }.onFailure { w.close() }
            }
        }
        try {
            while (true) {
                val m = w.read() ?: break
                when (m.optString("t")) {
                    "ping" -> w.send(msg("pong", "at" to m.optLong("at")))
                    "run" -> run(w, bind = sock.localAddress.hostAddress ?: "", layers = m.optString("layers"), model = m.optString("model"))
                    "stop" -> { engine.stop(); _link.value = _link.value.copy(layers = "", model = "") }
                    "mesh" -> _mesh.value = MeshView(
                        devices = m.optJSONArray("devices")?.let { a ->
                            List(a.length()) { i -> a.getJSONObject(i).let { d ->
                                MeshDevice(d.optString("name"), d.optString("kind"), d.optString("role"), d.optString("layers"),
                                    if (d.isNull("rtt")) null else d.optDouble("rtt"))
                            } }
                        } ?: emptyList(),
                        model = m.optString("model"), status = m.optString("status"),
                        tps = if (m.isNull("tps")) null else m.optDouble("tps"),
                    )
                    "bye" -> { _link.value = Link(state = Link.State.FAILED, error = m.optString("reason")); break }
                }
            }
        } finally {
            // keep the engine (and the layers it holds) through a blip: the run survives while we reconnect.
            // It stops on the Host's "stop", a new "run", or Leave.
            reporter.cancel()
            w.close()
        }
    }

    /** Start the engine on the link address, then tell the Host where to find it. */
    private fun run(w: Wire, bind: String, layers: String, model: String) = scope.launch {
        engine.startHelper(bind, threads = (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(2)).join()
        val s = withTimeoutOrNull(20_000) {
            engine.state.first { it.status == EngineState.Status.RUNNING || it.status == EngineState.Status.FAILED }
        }
        if (s?.status == EngineState.Status.RUNNING) {
            _link.value = _link.value.copy(layers = layers, model = model)
            runCatching { w.send(msg("ready", "addr" to s.address)) }
        } else {
            runCatching { w.send(msg("failed", "reason" to "engine did not start")) }
        }
    }

    /**
     * The first address is the preferred link (the Host lists its USB cable first). Try it for a few seconds
     * before falling back, so a Host that is just restarting does not push us onto slow Wi-Fi.
     */
    private fun connectAny(invite: Invite): Socket? {
        val first = invite.hosts.firstOrNull() ?: return null
        repeat(5) {
            runCatching { Socket().apply { connect(InetSocketAddress(first, invite.port), 2_000) } }.getOrNull()?.let { return it }
            Thread.sleep(1_000)
        }
        return invite.hosts.drop(1).firstNotNullOfOrNull { ip ->
            runCatching { Socket().apply { connect(InetSocketAddress(ip, invite.port), 4_000) } }.getOrNull()
        }
    }
}

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
        sock.soTimeout = 15_000
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
                runCatching { w.send(msg("specs", "specs" to Specs.read(ctx).toJson())) }.onFailure { w.close() }
            }
        }
        try {
            while (true) {
                val m = w.read() ?: break
                when (m.optString("t")) {
                    "ping" -> w.send(msg("pong", "at" to m.optLong("at")))
                    "run" -> run(w, bind = sock.localAddress.hostAddress ?: "", layers = m.optString("layers"), model = m.optString("model"))
                    "stop" -> { engine.stop(); _link.value = _link.value.copy(layers = "", model = "") }
                    "bye" -> { _link.value = Link(state = Link.State.FAILED, error = m.optString("reason")); break }
                }
            }
        } finally {
            reporter.cancel()
            w.close()
            engine.stop()
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

    private fun connectAny(invite: Invite): Socket? = invite.hosts.firstNotNullOfOrNull { ip ->
        runCatching { Socket().apply { connect(InetSocketAddress(ip, invite.port), 4_000) } }.getOrNull()
    }
}

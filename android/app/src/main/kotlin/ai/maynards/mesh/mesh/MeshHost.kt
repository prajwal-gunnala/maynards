package ai.maynards.mesh.mesh

import ai.maynards.mesh.engine.Net
import ai.maynards.mesh.engine.Specs
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** A Helper as the Host sees it. */
data class Peer(
    val id: String,
    val specs: Specs,
    val addr: String,          // the Helper's IP, as seen from the Host
    val rttMs: Double = -1.0,  // median of the last 10 pings (Wi-Fi spikes would skew an average)
    val rttWorstMs: Double = -1.0,
    val models: Map<String, Long> = emptyMap(),  // model files this device offers (laptops), name -> bytes
    val filesPort: Int = 0,
) {
    fun modelUrl(file: String) = "http://$addr:$filesPort/models/$file"
}

/**
 * The Host side of the mesh: listens on port 7070, lets Helpers join with a one-time token
 * (and later with the secret it gave them), and keeps each Helper's specs and link speed live.
 */
class MeshHost(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = ctx.getSharedPreferences("mesh-host", Context.MODE_PRIVATE)
    private val conns = ConcurrentHashMap<String, Wire>()
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null

    val meshId: String = prefs.getString("mesh", null) ?: Secrets.random(4).also { prefs.edit().putString("mesh", it).apply() }

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers

    private val _token = MutableStateFlow(Secrets.random())
    val token: StateFlow<String> = _token

    /** Messages from Helpers that the run logic cares about (ready, failed). */
    private val _events = MutableSharedFlow<Pair<String, JSONObject>>(extraBufferCapacity = 32)
    val events: SharedFlow<Pair<String, JSONObject>> = _events

    fun invite(): Invite = Invite(meshId, Net.addresses().map { it.ip }, CONTROL_PORT, _token.value)

    /** The laptop has no camera to scan with: it reads the invite over USB instead (adb shell cat …/invite.json). */
    private fun saveInvite() {
        runCatching { java.io.File(ctx.getExternalFilesDir(null), "invite.json").writeText(invite().toJson()) }
    }

    fun start() {
        if (server != null) return
        val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(CONTROL_PORT)) }
        server = s
        saveInvite()
        acceptJob = scope.launch {
            while (isActive) {
                val sock = runCatching { s.accept() }.getOrNull() ?: break
                // serve() throws on a socket that dies during the handshake or on a malformed
                // hello; unhandled, that cancels this accept loop and no phone can join again
                launch { runCatching { serve(sock) }.onFailure { runCatching { sock.close() } } }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        acceptJob?.cancel()
        conns.values.forEach { it.close() }
        conns.clear()
        _peers.value = emptyMap()
    }

    fun send(id: String, m: JSONObject): Boolean = conns[id]?.let { runCatching { it.send(m) }.isSuccess } ?: false

    fun forget(id: String) {
        prefs.edit().remove("secret:$id").apply()
        conns.remove(id)?.close()
        _peers.update { it - id }
    }

    private suspend fun serve(sock: Socket) {
        sock.tcpNoDelay = true
        sock.soTimeout = 5_000              // hello must come quickly
        val wire = Wire(sock)
        val hello = runCatching { wire.read() }.getOrNull()
        if (hello == null || hello.optString("t") != "hello") { wire.close(); return }

        val id = hello.optString("id")
        val specs = runCatching { Specs.fromJson(hello.getJSONObject("specs")) }.getOrNull()
        if (id.isBlank() || specs == null) { wire.send(msg("bye", "reason" to "bad hello")); wire.close(); return }

        val known = prefs.getString("secret:$id", null)
        val secret = when {
            known != null && Secrets.same(known, hello.optString("secret")) -> known
            Secrets.same(_token.value, hello.optString("token")) -> {
                _token.value = Secrets.random()                 // one-time: the next device needs a fresh QR
                saveInvite()
                Secrets.random(32).also { prefs.edit().putString("secret:$id", it).apply() }
            }
            else -> { wire.send(msg("bye", "reason" to "not paired")); wire.close(); return }
        }

        conns.put(id, wire)?.close()                          // a reconnect replaces the old link
        wire.send(msg("welcome", "secret" to secret, "host" to android.os.Build.MODEL, "mesh" to meshId))
        val addr = sock.inetAddress.hostAddress ?: ""
        val offered = hello.optJSONArray("models")?.let { a ->
            (0 until a.length()).associate { a.getJSONObject(it).getString("file") to a.getJSONObject(it).getLong("bytes") }
        } ?: emptyMap()
        _peers.update { it + (id to Peer(id, specs, addr, models = offered, filesPort = hello.optInt("files_port"))) }
        // 60 s, matching the Helper: a phone busy loading several GB of layers can be quiet for
        // a while, and treating that as a dead link ended runs mid-load
        sock.soTimeout = 60_000                               // pongs arrive every 2 s

        val rtts = ArrayDeque<Double>()
        val pinger = scope.launch {
            while (isActive) {
                runCatching { wire.send(msg("ping", "at" to System.nanoTime())) }.onFailure { wire.close() }
                delay(2_000)
            }
        }
        try {
            while (true) {
                val m = wire.read() ?: break
                when (m.optString("t")) {
                    "specs" -> runCatching { Specs.fromJson(m.getJSONObject("specs")) }.getOrNull()?.let { s ->
                        _peers.update { p -> p[id]?.let { p + (id to it.copy(specs = s)) } ?: p }
                    }
                    "pong" -> {
                        rtts.addLast((System.nanoTime() - m.optLong("at")) / 1e6)
                        if (rtts.size > 10) rtts.removeFirst()
                        val median = rtts.sorted()[rtts.size / 2]
                        _peers.update { p -> p[id]?.let { p + (id to it.copy(rttMs = median, rttWorstMs = rtts.max())) } ?: p }
                    }
                    else -> _events.tryEmit(id to m)
                }
            }
        } catch (_: Exception) {
        } finally {
            pinger.cancel()
            // Only announce "gone" if this connection is still the live one. A helper that
            // reconnects replaces the entry above, and the old connection's teardown used to
            // emit "gone" anyway, which Runner treats as "a helper left" and ends the run.
            if (conns.remove(id, wire)) {
                _peers.update { it - id }
                _events.tryEmit(id to msg("gone"))
            }
            wire.close()
        }
    }
}

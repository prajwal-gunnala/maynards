package ai.maynards.mesh.mesh

import ai.maynards.mesh.brain.LayerStore
import ai.maynards.mesh.engine.Engine
import ai.maynards.mesh.engine.EngineState
import ai.maynards.mesh.engine.Specs
import android.content.Context
import okhttp3.WebSocket
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

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
    private var remoteSignal: WebSocket? = null
    private var remotePeer: RtcPeer? = null
    private val remoteRpcStreams = ConcurrentHashMap<String, DataChannelStream>()
    private val remoteRpcBridges = ConcurrentHashMap<String, Closeable>()
    private val attachedRpcChannels = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var remoteRpcPort: Int? = null
    private val remoteRpcExecutor = Executors.newCachedThreadPool { r -> Thread(r, "mesh-helper-rpc-channel").apply { isDaemon = true } }
    private val remoteProbeExecutor = Executors.newCachedThreadPool { r -> Thread(r, "mesh-helper-probe").apply { isDaemon = true } }

    private val _link = MutableStateFlow(Link())
    val link: StateFlow<Link> = _link

    private val _mesh = MutableStateFlow(MeshView())
    val mesh: StateFlow<MeshView> = _mesh

    /** Layers kept on this phone, so the Host does not send them again. */
    val store = LayerStore(ctx)

    /** The last invite, so the app can rejoin after a restart. */
    val savedInvite: Invite? get() = prefs.getString("invite", null)?.let(Invite::parse)

    fun join(invite: Invite) {
        leave()
        prefs.edit().putString("invite", invite.toJson()).apply()
        if (invite.remoteUrl != null && invite.remoteSession != null) {
            job = scope.launch {
                _link.value = Link(state = Link.State.CONNECTING)
                try {
                    val error = runCatching { sessionRemote(invite) }.exceptionOrNull()
                    if (_link.value.state != Link.State.FAILED) {
                        _link.value = Link(state = Link.State.FAILED, error = error?.message ?: "remote session ended")
                    }
                } finally {
                    remoteRpcPort = null
                    remoteRpcBridges.values.forEach { runCatching { it.close() } }; remoteRpcBridges.clear()
                    remoteRpcStreams.values.forEach { runCatching { it.close() } }; remoteRpcStreams.clear()
                    attachedRpcChannels.clear()
                    remotePeer?.close(); remotePeer = null
                    remoteSignal?.close(1000, "remote session ended"); remoteSignal = null
                    engine.stop()
                }
            }
            return
        }
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
        remoteRpcPort = null
        remoteRpcBridges.values.forEach { runCatching { it.close() } }; remoteRpcBridges.clear()
        remoteRpcStreams.values.forEach { runCatching { it.close() } }; remoteRpcStreams.clear()
        attachedRpcChannels.clear()
        remotePeer?.close(); remotePeer = null
        remoteSignal?.close(1000, "helper left"); remoteSignal = null
        engine.stop()
        _link.value = Link()
    }

    private suspend fun session(invite: Invite) {
        val sock = connectAny(invite) ?: throw IllegalStateException("cannot reach the Host")
        sock.tcpNoDelay = true
        sock.soTimeout = 60_000     // the Host pings every 2 s; allow for busy moments during a run
        sessionWire(invite, Wire(sock), sock.inetAddress.hostAddress ?: "", sock.localAddress.hostAddress ?: "", remote = false)
    }

    private suspend fun sessionWire(invite: Invite, w: Wire, hostIp: String, engineBind: String, remote: Boolean) {
        wire = w
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
        _link.value = Link(Link.State.JOINED, first.optString("host"), hostIp)

        val reporter = scope.launch {
            while (isActive) {
                delay(2_000)
                val running = engine.state.value.let { if (it.status == EngineState.Status.RUNNING) it.address else "" }
                runCatching { w.send(msg("specs", "specs" to Specs.read(ctx).toJson(), "engine" to running, "store" to store.state.value.json())) }.onFailure { w.close() }
            }
        }
        try {
            while (true) {
                val m = w.read() ?: break
                when (m.optString("t")) {
                    "ping" -> w.send(msg("pong", "at" to m.optLong("at")))
                    "run" -> run(w, bind = engineBind, layers = m.optString("layers"), model = m.optString("model"),
                        modelFile = m.optString("model_file"), remote = remote)
                    // the Host names the models that need helpers: keep their layers on this phone, once
                    "store" -> m.optJSONArray("models")?.let { a ->
                        store.fill(File(ctx.getExternalFilesDir(null), "models"), List(a.length()) { a.getString(it) })
                    }
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

    private suspend fun sessionRemote(invite: Invite) {
        val service = RemoteSignaling(invite.remoteUrl!!)
        val sessionId = invite.remoteSession!!
        // Resume credentials belong to one pairing session. Keying only by mesh
        // reuses stale credentials when a Host creates a fresh invite for that mesh.
        val resumeKey = "remote-resume:$sessionId"
        val signalingToken = prefs.getString(resumeKey, null)?.takeIf { it.isNotBlank() } ?: invite.token
        val turn = service.turnCredentials(sessionId, "helper", signalingToken).iceConfig()
        val control = CompletableDeferred<Wire>()
        val signalingFailed = CompletableDeferred<String>()
        val pendingSignals = ArrayList<Pair<String, JSONObject>>()
        fun dispatchSignal(type: String, payload: JSONObject) {
            when (type) {
                    "offer" -> remotePeer?.acceptOffer(payload.optString("sdp"))
                "ice" -> remotePeer?.addIce(payload)
                "hangup" -> signalingFailed.complete("host ended the remote session")
            }
        }
        remoteSignal = service.connect(sessionId, "helper", signalingToken, object : RemoteSignaling.Listener {
            override fun onOpen(socket: WebSocket) = Unit
            override fun onMessage(type: String, payload: JSONObject) {
                when (type) {
                    "joined" -> prefs.edit().putString(resumeKey, payload.optString("resumeToken")).apply()
                    else -> synchronized(pendingSignals) {
                        if (remotePeer == null) pendingSignals += type to payload else dispatchSignal(type, payload)
                    }
                }
            }
            override fun onClosed(code: Int, reason: String) { signalingFailed.complete("signaling closed: $reason") }
            override fun onFailure(message: String) { signalingFailed.complete(message) }
        })
        remotePeer = RtcPeer(ctx, listOf(turn), forceRelay = invite.forceRelay, signal = { type, payload ->
            remoteSignal?.send(JSONObject().put("type", type).put("payload", payload).toString())
        }, onChannel = { channel ->
            val label = channel.label()
            when {
                label == "control" -> control.complete(Wire(DataChannelStream(channel)))
                label.startsWith("rpc:") -> {
                    val stream = DataChannelStream(channel)
                    remoteRpcStreams[label] = stream
                    remoteRpcPort?.let { attachRemoteRpc(label, stream, it) }
                }
                label == "probe" -> {
                    val stream = DataChannelStream(channel)
                    val task = remoteProbeExecutor.submit<BinaryProbeResult> { BinaryTransportProbe.helper(stream) }
                    scope.launch {
                        val result = runCatching { task.get(90, TimeUnit.SECONDS) }
                            .getOrElse { stream.close(); BinaryProbeResult(false, 0, 0, it.message ?: "binary probe timed out") }
                        if (!result.passed) signalingFailed.complete("binary transport integrity failed: ${result.error}")
                    }
                }
                else -> channel.close()
            }
        }, onState = { state ->
            _link.value = _link.value.copy(hostIp = "WebRTC · $state")
            if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                val old = remotePeer
                scope.launch {
                    delay(15_000)
                    if (remotePeer === old && old?.iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                        signalingFailed.complete("WebRTC stayed disconnected for 15 seconds; the inference session was stopped")
                        wire?.close()
                    }
                }
            }
            if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED) {
                signalingFailed.complete("WebRTC connection $state")
                wire?.close()
            }
        }, onError = { signalingFailed.complete(it) })
        synchronized(pendingSignals) {
            pendingSignals.forEach { (type, payload) -> dispatchSignal(type, payload) }
            pendingSignals.clear()
        }
        val result = kotlinx.coroutines.withTimeoutOrNull(60_000) {
            kotlinx.coroutines.selects.select<Wire> {
                control.onAwait { it }
                signalingFailed.onAwait { throw IllegalStateException(it) }
            }
        } ?: throw IllegalStateException("timed out waiting for the WebRTC control channel")
        sessionWire(invite, result, "remote peer", "127.0.0.1", remote = true)
    }

    /** Start the engine on the link address, then tell the Host where to find it. */
    private fun run(w: Wire, bind: String, layers: String, model: String, modelFile: String, remote: Boolean) = scope.launch {
        if (remote && modelFile.isNotBlank()) {
            val models = File(ctx.getExternalFilesDir(null), "models")
            val localModel = File(models, modelFile)
            if (localModel.isFile && localModel.canRead()) {
                _link.value = _link.value.copy(model = model, layers = layers, error = "preparing local RPC tensor cache")
                val layerBounds = layers.split('-').mapNotNull(String::toIntOrNull)
                if (layerBounds.size != 2 || layerBounds[0] < 0 || layerBounds[1] < layerBounds[0]) {
                    runCatching { w.send(msg("failed", "reason" to "invalid assigned layer range: $layers")) }
                    return@launch
                }
                val cacheJob = store.fill(models, listOf(modelFile), layerBounds[0]..layerBounds[1])
                val finished = withTimeoutOrNull(10 * 60_000L) { cacheJob.join(); true } ?: false
                val cache = store.state.value
                if (!finished || cache.working || cache.error.isNotBlank()) {
                    runCatching { w.send(msg("failed", "reason" to "local RPC tensor cache preparation failed: ${cache.error.ifBlank { "timed out" }}")) }
                    return@launch
                }
            } else {
                runCatching { w.send(msg("failed", "reason" to "Helper model file is missing: $modelFile")) }
                return@launch
            }
        }
        engine.startHelper(bind, threads = (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(2)).join()
        val s = withTimeoutOrNull(20_000) {
            engine.state.first { it.status == EngineState.Status.RUNNING || it.status == EngineState.Status.FAILED }
        }
        if (s?.status == EngineState.Status.RUNNING) {
            if (remote) {
                val port = s.address.substringAfterLast(':').toIntOrNull()
                if (port == null) {
                    engine.stop(); runCatching { w.send(msg("failed", "reason" to "Helper RPC server address is invalid")) }; return@launch
                }
                remoteRpcPort = port
                remoteRpcStreams.forEach { (label, stream) -> attachRemoteRpc(label, stream, port) }
            }
            _link.value = _link.value.copy(layers = layers, model = model)
            runCatching { w.send(msg("ready", "addr" to s.address)) }
        } else {
            runCatching { w.send(msg("failed", "reason" to "engine did not start")) }
        }
    }

    /** Each llama.cpp TCP connection gets its own DataChannel; RPC opens several during startup. */
    private fun attachRemoteRpc(label: String, stream: DataChannelStream, port: Int) {
        if (!attachedRpcChannels.add(label)) return
        remoteRpcExecutor.execute {
            runCatching {
                val rpcSocket = Socket().apply {
                    connect(InetSocketAddress("127.0.0.1", port), 5_000)
                    tcpNoDelay = true
                }
                val bridge = TcpDataChannelBridge.bridgeConnected(
                    rpcSocket,
                    stream,
                    onError = { _link.value = _link.value.copy(error = "RPC tunnel $label failed: ${it.message}") },
                    onClosed = {
                        remoteRpcStreams.remove(label, stream)
                        remoteRpcBridges.remove(label)
                        attachedRpcChannels.remove(label)
                    },
                )
                remoteRpcBridges[label] = bridge
                if (!stream.isOpen) remoteRpcBridges.remove(label, bridge)
                android.util.Log.i("MeshRTC", "bridged $label to helper RPC port $port")
            }.onFailure {
                attachedRpcChannels.remove(label)
                remoteRpcStreams.remove(label)
                stream.close()
                _link.value = _link.value.copy(error = "RPC tunnel $label failed: ${it.message}")
            }
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

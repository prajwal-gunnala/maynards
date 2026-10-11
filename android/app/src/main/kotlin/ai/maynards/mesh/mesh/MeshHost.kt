package ai.maynards.mesh.mesh

import ai.maynards.mesh.engine.Net
import ai.maynards.mesh.engine.Specs
import ai.maynards.mesh.brain.LocalApiKey
import android.content.Context
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import okhttp3.WebSocket
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** A Helper as the Host sees it. */
data class Peer(
    val id: String,
    val specs: Specs,
    val addr: String,          // the Helper's IP, as seen from the Host
    val rttMs: Double = -1.0,  // median of the last 10 pings (Wi-Fi spikes would skew an average)
    val rttWorstMs: Double = -1.0,
    val rpcProxyAddress: String? = null,
    val linkKind: String = "network",
    val rpcEvent: String = "",
    val rpcSequence: Long = 0,
    val rpcEventAtMs: Long = 0,
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
    val apiKey: String = LocalApiKey.get(ctx)
    private val conns = ConcurrentHashMap<String, Wire>()
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private var remoteSignal: WebSocket? = null
    private var remotePeer: RtcPeer? = null
    private var remoteRpcBridge: TcpDataChannelBridge? = null
    private var remoteRpcServer: ServerSocket? = null
    private val remoteRpcStreams = LinkedBlockingQueue<DataChannelStream>()
    private val nextRpcChannelId = AtomicInteger(1)
    private var pendingRemoteControl: Wire? = null
    private var remoteRpcAddress: String? = null
    private val remoteProbeExecutor = Executors.newCachedThreadPool { r -> Thread(r, "mesh-transport-probe").apply { isDaemon = true } }
    private var remoteInviteToken: String? = null
    private var remoteService: RemoteSignaling? = null
    private var routeJob: Job? = null
    private var remoteForceRelay = false
    private val _remoteInvite = MutableStateFlow<Invite?>(null)
    val remoteInvite: StateFlow<Invite?> = _remoteInvite
    private val _remoteState = MutableStateFlow("idle")
    val remoteState: StateFlow<String> = _remoteState
    private val _remoteRoute = MutableStateFlow<RtcPeer.Route?>(null)
    val remoteRoute: StateFlow<RtcPeer.Route?> = _remoteRoute
    private val _remoteProbe = MutableStateFlow<BinaryProbeResult?>(null)
    val remoteProbe: StateFlow<BinaryProbeResult?> = _remoteProbe

    val meshId: String = prefs.getString("mesh", null) ?: Secrets.random(4).also { prefs.edit().putString("mesh", it).apply() }

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers

    private val _token = MutableStateFlow(Secrets.random())
    val token: StateFlow<String> = _token

    /** Messages from Helpers that the run logic cares about (ready, failed). */
    private val _events = MutableSharedFlow<Pair<String, JSONObject>>(extraBufferCapacity = 32)
    val events: SharedFlow<Pair<String, JSONObject>> = _events

    /**
     * Local pairing can be constrained to one interface selected by the owner.
     * Null keeps the existing automatic address preference and fallback behavior.
     */
    fun invite(hostIp: String? = null): Invite {
        val addresses = Net.addresses()
        val selected = if (hostIp == null) addresses else addresses.filter { it.ip == hostIp }
        return Invite(meshId, selected.map { it.ip }, CONTROL_PORT, _token.value)
    }

    /** Creates a public pairing invite; the backend coordinates peers but never sees RPC bytes. */
    fun createRemoteInvite(serviceUrl: String, forceRelay: Boolean = false): Invite {
        stopRemote()
        val service = RemoteSignaling(serviceUrl)
        val session = service.createSession()
        val turn = service.turnCredentials(session.id, "host", session.hostToken)
        remoteService = service
        remoteInviteToken = session.joinToken
        remoteForceRelay = forceRelay
        _remoteInvite.value = Invite(meshId, emptyList(), CONTROL_PORT, session.joinToken,
            serviceUrl.trimEnd('/'), session.id, forceRelay)
        _remoteState.value = "waiting for helper"
        _remoteProbe.value = null
        saveInvite()
        remoteSignal = service.connect(session.id, "host", session.hostToken, object : RemoteSignaling.Listener {
            override fun onOpen(socket: WebSocket) { _remoteState.value = "signaling connected; waiting for helper" }
            override fun onMessage(type: String, payload: JSONObject) {
                when (type) {
                    "peer-joined" -> {
                        if (remotePeer != null) closeRemotePeer("new helper transport session")
                        createRemotePeer(service, session.id, session.joinToken, turn.iceConfig(), forceRelay)
                    }
                    "answer" -> remotePeer?.acceptAnswer(payload.optString("sdp"))
                    "ice" -> remotePeer?.addIce(payload)
                    "hangup" -> closeRemotePeer("helper ended the session")
                    else -> Unit
                }
            }
            override fun onClosed(code: Int, reason: String) {
                if (_remoteState.value == "connected") _remoteState.value = "signaling disconnected; checking peer link"
            }
            override fun onFailure(message: String) { _remoteState.value = "signaling error: $message" }
        })
        return _remoteInvite.value!!
    }

    /** The laptop has no camera to scan with: it reads the invite over USB instead (adb shell cat …/invite.json). */
    private fun saveInvite() {
        runCatching { java.io.File(ctx.getExternalFilesDir(null), "invite.json").writeText((_remoteInvite.value ?: invite()).toJson()) }
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
        stopRemote()
        runCatching { server?.close() }
        server = null
        acceptJob?.cancel()
        conns.values.forEach { it.close() }
        conns.clear()
        _peers.value = emptyMap()
    }

    private fun createRemotePeer(service: RemoteSignaling, sessionId: String, joinToken: String,
                                 turn: RtcPeer.IceServerConfig, forceRelay: Boolean) {
        if (remotePeer != null) return
        _remoteProbe.value = null
        _remoteRoute.value = null
        _remoteState.value = if (forceRelay) "negotiating TURN-only path" else "negotiating WebRTC path"
        val socket = remoteSignal ?: return
        remotePeer = runCatching {
            RtcPeer(ctx, listOf(turn), forceRelay,
                signal = { type, payload ->
                    if (!socket.send(JSONObject().put("type", type).put("payload", payload).toString())) {
                        _remoteState.value = "could not send WebRTC signal"
                    }
                },
                onChannel = { channel -> remoteChannel(channel, joinToken) },
                onState = { state ->
                    _remoteState.value = "ICE $state"
                    if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                        _remoteState.value = "connected"
                        routeJob?.cancel()
                        routeJob = scope.launch {
                            val rtts = ArrayDeque<Double>()
                            while (isActive) {
                                val response = CompletableDeferred<RtcPeer.Route?>()
                                remotePeer?.selectedRoute { response.complete(it) } ?: break
                                val route = withTimeoutOrNull(2_000) { response.await() }
                                route?.rttMs?.let { value ->
                                    rtts.addLast(value)
                                    if (rtts.size > 10) rtts.removeFirst()
                                }
                                val jitter = rtts.toList().zipWithNext { a, b -> kotlin.math.abs(a - b) }.average()
                                    .takeIf { it.isFinite() }
                                _remoteRoute.value = route?.copy(jitterMs = jitter)
                                delay(2_000)
                            }
                        }
                    }
                    if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                        _remoteState.value = "ICE disconnected; waiting up to 15 seconds for recovery"
                        val old = remotePeer
                        scope.launch {
                            delay(15_000)
                            if (remotePeer === old && old?.iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                                closeRemotePeer("WebRTC disconnect timeout")
                            }
                        }
                    }
                    if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED) {
                        closeRemotePeer("WebRTC $state")
                    }
                },
                onError = { _remoteState.value = it },
            ).also { peer ->
                peer.createChannel("control")
                peer.createChannel("rpc:0")
                peer.createChannel("probe")
                peer.createOffer()
            }
        }.onFailure { _remoteState.value = "WebRTC setup failed: ${it.message}" }.getOrNull()
    }

    private fun remoteChannel(channel: DataChannel, joinToken: String) {
        val label = channel.label()
        when {
            label == "control" -> {
                ensureRemoteRpcProxy()
                pendingRemoteControl = Wire(DataChannelStream(channel))
                attachRemoteControlIfVerified(joinToken)
            }
            label.startsWith("rpc:") -> {
                val stream = DataChannelStream(channel)
                remoteRpcStreams.offer(stream)
                ensureRemoteRpcProxy()
            }
            label == "probe" -> {
                val stream = DataChannelStream(channel)
                _remoteState.value = "checking bidirectional 8 MiB DataChannel integrity"
                val task = remoteProbeExecutor.submit<BinaryProbeResult> { BinaryTransportProbe.host(stream) }
                scope.launch {
                    val result = runCatching { task.get(90, TimeUnit.SECONDS) }
                        .getOrElse { stream.close(); BinaryProbeResult(false, 0, 0, it.message ?: "binary probe timed out") }
                    _remoteProbe.value = result
                    if (result.passed) {
                        _remoteState.value = "binary integrity passed · ${"%.2f".format(result.megabytesPerSecond)} MiB/s"
                        attachRemoteControlIfVerified(joinToken)
                    } else {
                        _remoteState.value = "binary integrity failed: ${result.error}"
                        closeRemotePeer(_remoteState.value)
                    }
                }
            }
            else -> channel.close()
        }
    }

    private fun attachRemoteControlIfVerified(joinToken: String) {
        val control = pendingRemoteControl ?: return
        if (_remoteProbe.value?.passed != true) return
        val address = remoteRpcAddress ?: return
        pendingRemoteControl = null
        acceptRemoteControl(control, joinToken, address)
    }

    private fun ensureRemoteRpcProxy(): String {
        if (remoteRpcBridge != null) {
            val s = remoteRpcServer ?: error("RPC proxy listener is missing")
            return "127.0.0.1:${s.localPort}"
        }
        // llama.cpp RPC is configured with the IPv4 loopback literal below. Bind
        // the proxy to the same family explicitly; Android may resolve the generic
        // loopback address to ::1, which leaves 127.0.0.1 refusing the RPC client.
        val listener = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", 0)) }
        remoteRpcServer = listener
        remoteRpcAddress = "127.0.0.1:${listener.localPort}"
        android.util.Log.i("MeshRTC", "RPC proxy listening on ${listener.inetAddress.hostAddress}:${listener.localPort}")
        remoteRpcBridge = TcpDataChannelBridge(listener, {
            remoteRpcStreams.poll() ?: run {
                val peer = remotePeer ?: throw IllegalStateException("WebRTC peer is not ready for an RPC channel")
                val id = nextRpcChannelId.getAndIncrement()
                android.util.Log.i("MeshRTC", "opening RPC DataChannel rpc:$id for a new TCP connection")
                peer.createChannel("rpc:$id")
                remoteRpcStreams.poll(15, TimeUnit.SECONDS)
                    ?: throw java.net.SocketTimeoutException("RPC DataChannel rpc:$id did not arrive within 15 seconds")
            }
        }) { _remoteState.value = "RPC bridge error: ${it.message}" }.also { it.start() }
        return remoteRpcAddress!!
    }

    private fun closeRemotePeer(reason: String) {
        if (remotePeer == null && remoteRpcBridge == null) return
        remoteRpcBridge?.close(); remoteRpcBridge = null
        runCatching { remoteRpcServer?.close() }; remoteRpcServer = null
        remoteRpcStreams.forEach { runCatching { it.close() } }
        remoteRpcStreams.clear()
        pendingRemoteControl?.close(); pendingRemoteControl = null
        remoteRpcAddress = null
        remotePeer?.close(); remotePeer = null
        routeJob?.cancel(); routeJob = null
        _remoteState.value = reason
    }

    private fun stopRemote() {
        closeRemotePeer("remote session stopped")
        remoteSignal?.close(1000, "session stopped"); remoteSignal = null
        remoteService = null
        remoteInviteToken = null
        _remoteInvite.value = null
        _remoteRoute.value = null
        _remoteProbe.value = null
    }

    fun send(id: String, m: JSONObject): Boolean = conns[id]?.let { runCatching { it.send(m) }.isSuccess } ?: false

    fun remoteRpcReady(id: String): Boolean = peers.value[id]?.rpcProxyAddress != null && remoteRpcStreams.peek()?.isOpen == true

    fun forget(id: String) {
        prefs.edit().remove("secret:$id").apply()
        conns.remove(id)?.close()
        _peers.update { it - id }
    }

    /** Attaches the control stream delivered by the authenticated WebRTC pairing session. */
    fun acceptRemoteControl(wire: Wire, joinToken: String, rpcProxyAddress: String) {
        scope.launch {
            runCatching { serve(wire, null, "webrtc", joinToken, rpcProxyAddress) }
                .onFailure { wire.close() }
        }
    }

    private suspend fun serve(sock: Socket) {
        sock.tcpNoDelay = true
        sock.soTimeout = 5_000              // hello must come quickly
        serve(Wire(sock), sock, sock.inetAddress.hostAddress ?: "", null, null)
    }

    private suspend fun serve(wire: Wire, sock: Socket?, peerAddress: String, remoteJoinToken: String?, rpcProxyAddress: String?) {
        val hello = runCatching { wire.read() }.getOrNull()
        if (hello == null || hello.optString("t") != "hello") { wire.close(); return }

        val id = hello.optString("id")
        val specs = runCatching { Specs.fromJson(hello.getJSONObject("specs")) }.getOrNull()
        if (id.isBlank() || specs == null) { wire.send(msg("bye", "reason" to "bad hello")); wire.close(); return }

        val known = prefs.getString("secret:$id", null)
        val secret = when {
            known != null && Secrets.same(known, hello.optString("secret")) -> known
            remoteJoinToken != null && Secrets.same(remoteJoinToken, hello.optString("token")) ->
                Secrets.random(32).also { prefs.edit().putString("secret:$id", it).apply() }
            Secrets.same(_token.value, hello.optString("token")) -> {
                _token.value = Secrets.random()                 // one-time: the next device needs a fresh QR
                saveInvite()
                Secrets.random(32).also { prefs.edit().putString("secret:$id", it).apply() }
            }
            else -> { wire.send(msg("bye", "reason" to "not paired")); wire.close(); return }
        }

        conns.put(id, wire)?.close()                          // a reconnect replaces the old link
        val welcome = msg("welcome", "secret" to secret, "host" to android.os.Build.MODEL, "mesh" to meshId)
        if (specs.kind == "laptop") welcome.put("api_key", apiKey)
        wire.send(welcome)
        val offered = hello.optJSONArray("models")?.let { a ->
            (0 until a.length()).associate { a.getJSONObject(it).getString("file") to a.getJSONObject(it).getLong("bytes") }
        } ?: emptyMap()
        val linkKind = when {
            rpcProxyAddress != null -> "WebRTC"
            sock == null -> "WebRTC"
            else -> Net.addresses().firstOrNull { it.ip == sock.localAddress.hostAddress }?.kind ?: "network"
        }
        _peers.update { it + (id to Peer(id, specs, peerAddress, rpcProxyAddress = rpcProxyAddress, linkKind = linkKind,
            models = offered, filesPort = hello.optInt("files_port"))) }
        ai.maynards.mesh.brain.Sense.add(specs.name, "joined · $linkKind · $peerAddress", ai.maynards.mesh.brain.SenseEvent.Kind.LINK)
        // 60 s, matching the Helper: a phone busy loading several GB of layers can be quiet for
        // a while, and treating that as a dead link ended runs mid-load
        sock?.soTimeout = 60_000                               // pongs arrive every 2 s

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
                        // the link's speed is its best recent ping, not the median: an idle Wi-Fi radio sleeps
                        // between pings 2 s apart and adds ~100 ms that is gone once the model runs (iQOO to
                        // iQOO over Wi-Fi: 103 ms median at idle, 17.6 ms with steady traffic)
                        val best = rtts.min()
                        _peers.update { p -> p[id]?.let { p + (id to it.copy(rttMs = best, rttWorstMs = rtts.max())) } ?: p }
                    }
                    "activity" -> {
                        val event = m.optString("event").take(32)
                        val sequence = m.optLong("sequence").coerceAtLeast(0)
                        if (event in setOf("graph_compute", "graph_recompute")) {
                            _peers.update { p -> p[id]?.let { peer ->
                                if (sequence > peer.rpcSequence) ai.maynards.mesh.brain.Sense.pulse(peer.specs.name, "computing its layers", ai.maynards.mesh.brain.SenseEvent.Kind.WORK)
                                if (sequence > peer.rpcSequence) p + (id to peer.copy(
                                    rpcEvent = event, rpcSequence = sequence, rpcEventAtMs = System.currentTimeMillis())) else p
                            } ?: p }
                        }
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
                ai.maynards.mesh.brain.Sense.add(_peers.value[id]?.specs?.name ?: specs.name, "left", ai.maynards.mesh.brain.SenseEvent.Kind.FAIL)
                _peers.update { it - id }
                _events.tryEmit(id to msg("gone"))
            }
            wire.close()
        }
    }
}

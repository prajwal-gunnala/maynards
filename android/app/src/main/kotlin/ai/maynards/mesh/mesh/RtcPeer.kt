package ai.maynards.mesh.mesh

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.MediaStream
import org.webrtc.RTCStatsReport
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The WebRTC peer used by MeshAI's cross-network transport. Signaling messages
 * are handed to the caller so the app can use its configured public WSS server.
 * It does not know about llama.cpp or change the inference protocol.
 */
class RtcPeer(
    context: Context,
    iceServers: List<IceServerConfig>,
    forceRelay: Boolean = false,
    private val signal: (type: String, payload: JSONObject) -> Unit,
    private val onChannel: (DataChannel) -> Unit,
    private val onState: (PeerConnection.IceConnectionState) -> Unit = {},
    private val onError: (String) -> Unit = {},
) : Closeable {
    data class IceServerConfig(val urls: List<String>, val username: String = "", val credential: String = "")
    data class Route(val localType: String, val remoteType: String, val rttMs: Double?, val jitterMs: Double? = null) {
        val relayed: Boolean get() = localType == "relay" || remoteType == "relay"
    }

    private val closed = AtomicBoolean(false)
    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    val iceConnectionState: PeerConnection.IceConnectionState get() = peer.iceConnectionState()
    private var remoteDescriptionSet = false
    private val pendingCandidates = ArrayList<IceCandidate>()

    init {
        val app = context.applicationContext
        synchronized(FACTORY_LOCK) {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions(),
                )
                factoryInitialized = true
            }
        }
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        // Direct same-LAN ICE is valid without STUN/TURN. Do not pass an empty
        // IceServer to libwebrtc; Android rejects it before ICE gathering starts.
        val configuredIceServers = iceServers.filter { it.urls.isNotEmpty() }.map { config ->
            val builder = PeerConnection.IceServer.builder(config.urls)
            if (config.username.isNotBlank()) builder.setUsername(config.username)
            if (config.credential.isNotBlank()) builder.setPassword(config.credential)
            builder.createIceServer()
        }
        val configuration = PeerConnection.RTCConfiguration(configuredIceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            if (forceRelay) iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        peer = factory.createPeerConnection(configuration, makeObserver())
            ?: throw IllegalStateException("WebRTC could not create a peer connection")
    }

    /** The host creates the reliable control channel before offering. RPC channels use rpc:<id>. */
    fun createChannel(label: String): DataChannel {
        check(!closed.get()) { "peer is closed" }
        val channel = peer.createDataChannel(label, DataChannel.Init().apply { ordered = true })
            ?: throw IllegalStateException("WebRTC could not create DataChannel $label")
        Log.i(TAG, "created local channel=$label")
        onChannel(channel)
        return channel
    }

    fun createOffer() = peer.createOffer(sdpObserver { description ->
        Log.i(TAG, "offer created")
        peer.setLocalDescription(setObserver {
            Log.i(TAG, "local offer set")
            signal("offer", JSONObject().put("sdp", description.description).put("type", "offer"))
        }, description)
    }, MediaConstraints())

    fun acceptOffer(sdp: String) {
        Log.i(TAG, "remote offer received")
        setRemote(SessionDescription(SessionDescription.Type.OFFER, sdp)) {
            peer.createAnswer(sdpObserver { description ->
                Log.i(TAG, "answer created")
                peer.setLocalDescription(setObserver {
                    Log.i(TAG, "local answer set")
                    signal("answer", JSONObject().put("sdp", description.description).put("type", "answer"))
                }, description)
            }, MediaConstraints())
        }
    }

    fun acceptAnswer(sdp: String) {
        Log.i(TAG, "remote answer received")
        setRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp), onComplete = {})
    }

    fun addIce(payload: JSONObject) {
        val candidate = runCatching {
            IceCandidate(payload.getString("sdpMid"), payload.getInt("sdpMLineIndex"), payload.getString("candidate"))
        }.getOrElse { onError("Invalid ICE candidate: ${it.message}"); return }
        synchronized(pendingCandidates) {
            if (!remoteDescriptionSet) pendingCandidates += candidate
            else if (!peer.addIceCandidate(candidate)) onError("WebRTC rejected an ICE candidate")
        }
    }

    /** Reports selected ICE candidate types and the transport RTT from live WebRTC stats. */
    fun selectedRoute(callback: (Route?) -> Unit) {
        peer.getStats { report -> callback(readRoute(report)) }
    }

    private fun setRemote(description: SessionDescription, onComplete: () -> Unit) {
        peer.setRemoteDescription(setObserver {
            synchronized(pendingCandidates) {
                remoteDescriptionSet = true
                pendingCandidates.forEach { if (!peer.addIceCandidate(it)) onError("WebRTC rejected a queued ICE candidate") }
                pendingCandidates.clear()
            }
            onComplete()
        }, description)
    }

    private fun readRoute(report: RTCStatsReport): Route? = runCatching {
        val stats = report.statsMap
        val selectedId = stats.values.firstOrNull { it.type == "transport" }
            ?.members?.get("selectedCandidatePairId") as? String
        val selected = selectedId?.let(stats::get) ?: stats.values.firstOrNull { s ->
            s.type == "candidate-pair" && (s.members["selected"] == true ||
                (s.members["state"] == "succeeded" && s.members["nominated"] == true))
        } ?: stats.values.firstOrNull { s -> s.type == "candidate-pair" && s.members["state"] == "succeeded" }
            ?: return null
        val local = stats[selected.members["localCandidateId"] as? String]?.members?.get("candidateType") as? String ?: "unknown"
        val remote = stats[selected.members["remoteCandidateId"] as? String]?.members?.get("candidateType") as? String ?: "unknown"
        val rtt = (selected.members["currentRoundTripTime"] as? Number)?.toDouble()?.times(1000.0)
        Route(local, remote, rtt)
    }.getOrNull()

    private fun makeObserver(): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) { Log.i(TAG, "signaling state=$state") }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { Log.i(TAG, "ICE state=$state"); onState(state) }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            Log.i(TAG, "local ICE candidate mid=${candidate.sdpMid} type=${candidate.sdp?.substringAfter(" typ ")?.substringBefore(' ')}")
            signal("ice", JSONObject().put("candidate", candidate.sdp)
                .put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex))
        }
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) { Log.i(TAG, "remote channel=${channel.label()} initialState=${channel.state()}"); onChannel(channel) }
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) = Unit
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = Unit
        override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) = Unit
    }

    private fun sdpObserver(onSuccess: (SessionDescription) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onSuccess(description)
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = onError("SDP creation failed: $error")
        override fun onSetFailure(error: String): Unit { Log.e(TAG, "SDP setup failed: $error"); onError("SDP setup failed: $error") }
    }

    private fun setObserver(onSuccess: () -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = onSuccess()
        override fun onCreateFailure(error: String) { Log.e(TAG, "SDP creation failed: $error"); onError("SDP creation failed: $error") }
        override fun onSetFailure(error: String) { Log.e(TAG, "SDP setup failed: $error"); onError("SDP setup failed: $error") }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        peer.close()
        peer.dispose()
        factory.dispose()
    }

    private class MediaConstraints : org.webrtc.MediaConstraints()

    companion object {
        private const val TAG = "MeshRTC"
        private val FACTORY_LOCK = Any()
        @Volatile private var factoryInitialized = false
    }
}

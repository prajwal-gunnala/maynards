package ai.maynards.mesh.mesh

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.Closeable
import java.net.URLEncoder
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class RemoteSession(
    val id: String,
    val hostToken: String,
    val joinToken: String,
    val expiresAtMs: Long,
)

data class TurnCredentials(
    val urls: List<String>,
    val username: String,
    val credential: String,
    val expiresAtMs: Long,
) {
    fun iceConfig() = RtcPeer.IceServerConfig(urls, username, credential)
}

/** HTTP/WSS client for the public signaling service. Call synchronous methods on Dispatchers.IO. */
class RemoteSignaling(private val serviceUrl: String, private val http: OkHttpClient = DEFAULT_HTTP) {
    init {
        val uri = URI(serviceUrl)
        val scheme = uri.scheme?.lowercase().orEmpty()
        require(scheme == "https" || scheme == "http") { "signaling URL must use HTTPS" }
        val host = uri.host ?: error("signaling URL has no host")
        val privateDevelopmentHost = runCatching {
            val address = InetAddress.getByName(host)
            address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || host.endsWith(".local")
        }.getOrDefault(host == "localhost" || host.endsWith(".local"))
        require(scheme == "https" || privateDevelopmentHost) {
            "public signaling must use HTTPS/WSS"
        }
    }

    fun createSession(): RemoteSession {
        val request = Request.Builder().url("${serviceUrl.trimEnd('/')}/v1/sessions")
            .post(ByteArray(0).toRequestBody(null)).build()
        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { "could not create remote pairing (${response.code}): ${body.take(200)}" }
            JSONObject(body).let {
                RemoteSession(it.getString("sessionId"), it.getString("hostToken"), it.getString("joinToken"), it.getLong("expiresAt"))
            }
        }
    }

    fun turnCredentials(sessionId: String, role: String, token: String): TurnCredentials {
        require(role == "host" || role == "helper")
        val url = "${serviceUrl.trimEnd('/')}/v1/turn?session=${enc(sessionId)}&role=$role&token=${enc(token)}"
        return http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { "could not obtain TURN credentials (${response.code}): ${body.take(200)}" }
            JSONObject(body).let { json ->
                val urls = json.getJSONArray("urls").let { a -> List(a.length()) { a.getString(it) } }
                TurnCredentials(urls, json.getString("username"), json.getString("credential"), json.getLong("expiresAt"))
            }
        }
    }

    fun connect(
        sessionId: String,
        role: String,
        token: String,
        listener: Listener,
    ): WebSocket {
        require(role == "host" || role == "helper")
        val wsUrl = serviceUrl.trimEnd('/').replaceFirst("^https://".toRegex(), "wss://")
            .replaceFirst("^http://".toRegex(), "ws://") +
            "/v1/signal?session=${enc(sessionId)}&role=$role&token=${enc(token)}"
        val request = Request.Builder().url(wsUrl).build()
        return http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen(webSocket)
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSONObject(text) }.getOrElse { listener.onFailure("invalid signaling message"); return }
                val type = message.optString("type")
                val payload = message.optJSONObject("payload") ?: JSONObject()
                if (type.isBlank()) listener.onFailure("signaling message has no type")
                else listener.onMessage(type, payload)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(code, reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure(t.message ?: "signaling connection failed")
        })
    }

    interface Listener {
        fun onOpen(socket: WebSocket)
        fun onMessage(type: String, payload: JSONObject)
        fun onClosed(code: Int, reason: String)
        fun onFailure(message: String)
    }

    companion object {
        private val DEFAULT_HTTP = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }
}

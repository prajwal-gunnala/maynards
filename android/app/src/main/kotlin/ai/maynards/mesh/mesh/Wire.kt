package ai.maynards.mesh.mesh

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The control link between Host and Helpers: one JSON object per line over TCP.
 * Every message has a type in "t":
 *   helper -> host: hello{id, token|secret, specs}, specs{specs}, pong{at}, ready{addr}, failed{reason}
 *   host -> helper: welcome{secret, host}, bye{reason}, ping{at}, run{port, layers}, stop{}
 */
const val CONTROL_PORT = 7070

class Wire private constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val transport: Closeable,
    val socket: Socket?,
) {
    constructor(socket: Socket) : this(socket.getInputStream(), socket.getOutputStream(), socket, socket)
    constructor(stream: DataChannelStream) : this(stream.input, stream.output, stream, null)

    private val reader: BufferedReader = input.bufferedReader()
    private val writer: BufferedWriter = output.bufferedWriter()

    fun send(msg: JSONObject) = synchronized(writer) {
        writer.write(msg.toString())
        writer.write("\n")
        writer.flush()
    }

    /** Next message, or null when the other side hung up. */
    fun read(): JSONObject? {
        val line = reader.readLine() ?: return null
        if (line.length > MAX_LINE) throw IllegalStateException("message too long")
        return JSONObject(line)
    }

    fun close() = runCatching { transport.close() }

    companion object {
        private const val MAX_LINE = 64 * 1024
    }
}

fun msg(type: String, vararg kv: Pair<String, Any?>): JSONObject =
    JSONObject().put("t", type).also { o -> kv.forEach { (k, v) -> o.put(k, v) } }

object Secrets {
    private val rng = SecureRandom()

    fun random(bytes: Int = 16): String = ByteArray(bytes).also(rng::nextBytes).joinToString("") { "%02x".format(it) }

    /** Constant-time compare, so timing does not leak how much of a secret matched. */
    fun same(a: String?, b: String?): Boolean =
        a != null && b != null && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

/** What the QR code carries: where the Host is, and a one-time token to join. */
data class Invite(
    val mesh: String,
    val hosts: List<String>,
    val port: Int,
    val token: String,
    val remoteUrl: String? = null,
    val remoteSession: String? = null,
    val forceRelay: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("mesh", mesh).put("hosts", org.json.JSONArray(hosts)).put("port", port).put("token", token)
        .also { j ->
            remoteUrl?.let { j.put("remote_url", it) }
            remoteSession?.let { j.put("remote_session", it) }
            if (remoteUrl != null) j.put("force_relay", forceRelay)
        }
        .toString()

    companion object {
        fun parse(text: String): Invite? = runCatching {
            val j = JSONObject(text.trim())
            val hosts = j.optJSONArray("hosts")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            Invite(j.getString("mesh"), hosts, j.optInt("port", CONTROL_PORT), j.getString("token"),
                j.optString("remote_url").takeIf { it.isNotBlank() }, j.optString("remote_session").takeIf { it.isNotBlank() },
                j.optBoolean("force_relay", false))
        }.getOrNull()
    }
}

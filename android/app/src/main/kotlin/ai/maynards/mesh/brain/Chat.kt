package ai.maynards.mesh.brain

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Turn(val role: String, val text: String)

/** How one answer went. */
data class Answer(
    val text: String, val tokens: Int, val tokPerSec: Double, val promptPerSec: Double, val firstTokenMs: Long,
    val route: String? = null,   // which device answered, from the X-Mesh-Route header
    val promptTokens: Int = 0, val promptMs: Double = 0.0, val predictedMs: Double = 0.0, val totalMs: Long = 0,
)

/** Where a streamed answer is: the server reading the prompt, then writing tokens. */
sealed class ChatProgress {
    /** From llama-server's prompt_progress: [processed] of [total] prompt tokens, [cache] reused. */
    data class Reading(val processed: Int, val total: Int, val cache: Int, val ms: Long) : ChatProgress()
    data class Writing(val tokens: Int, val firstTokenMs: Long) : ChatProgress()
}

/** Streams a chat answer from the local llama-server (OpenAI-compatible /v1/chat/completions). */
class Chat(private val endpoint: String, private val apiKey: String = "") {
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.MINUTES).build()

    fun ask(
        history: List<Turn>, onText: (String) -> Unit, image: String? = null, system: String? = null,
        onProgress: ((ChatProgress) -> Unit)? = null,
    ): Answer {
        val msgs = JSONArray()
        if (system != null) msgs.put(JSONObject().put("role", "system").put("content", system))
        history.forEachIndexed { i, t ->
            val last = i == history.lastIndex
            val content: Any = if (last && image != null) JSONArray()
                .put(JSONObject().put("type", "text").put("text", t.text))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image)))
            else t.text
            msgs.put(JSONObject().put("role", t.role).put("content", content))
        }
        val body = JSONObject().put("messages", msgs).put("stream", true).put("max_tokens", 1024)
            .put("stream_options", JSONObject().put("include_usage", true))
        if (onProgress != null) body.put("return_progress", true)
        val request = Request.Builder().url("$endpoint/v1/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val req = if (apiKey.isBlank()) request else request.newBuilder().header("Authorization", "Bearer $apiKey").build()

        val t0 = System.nanoTime()
        var first = -1L
        val out = StringBuilder()
        var timings: JSONObject? = null
        var route: String? = null
        var chunks = 0
        http.newCall(req).execute().use { resp ->
            route = resp.header("X-Mesh-Route")   // which device actually answered
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            val src = resp.body!!.source()
            while (!src.exhausted()) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ")
                if (data == "[DONE]") break
                val j = JSONObject(data)
                j.optJSONObject("timings")?.let { timings = it }
                j.optJSONObject("prompt_progress")?.let { p ->
                    onProgress?.invoke(ChatProgress.Reading(p.optInt("processed"), p.optInt("total"), p.optInt("cache"), p.optLong("time_ms")))
                }
                val d = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                val delta = if (d == null || d.isNull("content")) "" else d.optString("content")
                if (delta.isNotEmpty()) {
                    if (first < 0) first = (System.nanoTime() - t0) / 1_000_000
                    out.append(delta)
                    onText(out.toString())
                    onProgress?.invoke(ChatProgress.Writing(++chunks, first))
                }
            }
        }
        val t = timings
        return Answer(
            text = out.toString(),
            tokens = t?.optInt("predicted_n") ?: 0,
            tokPerSec = t?.optDouble("predicted_per_second") ?: 0.0,
            promptPerSec = t?.optDouble("prompt_per_second") ?: 0.0,
            firstTokenMs = first,
            route = route,
            promptTokens = t?.optInt("prompt_n") ?: 0,
            promptMs = t?.optDouble("prompt_ms") ?: 0.0,
            predictedMs = t?.optDouble("predicted_ms") ?: 0.0,
            totalMs = (System.nanoTime() - t0) / 1_000_000,
        )
    }
}

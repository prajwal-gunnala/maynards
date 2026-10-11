package ai.maynards.origami.core

import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** Calls MeshAI's local OpenAI-compatible endpoint; CAD generation remains deterministic in V1. */
class OrigamiAIBridge(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val apiKey: String = "",
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 600_000,
) {
    fun propose(description: String, verified: OrganizerSpec, imageDataUrl: String? = null): OrganizerSpec {
        require(description.isNotBlank()) { "Describe what you want to change" }
        verified.validate()

        val payload = JSONObject()
            .put("temperature", 0)
            .put("max_tokens", 160)
            .put("messages", org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                .put(JSONObject().put("role", "user").put("content", buildUserContent(description, verified, imageDataUrl))))
            .put("tools", org.json.JSONArray().put(toolSchema()))
            .put("tool_choice", JSONObject().put("type", "function")
                .put("function", JSONObject().put("name", "generate_organizer")))

        val connection = (URL("$endpoint/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "MeshAI returned HTTP $status: ${response.take(300)}" }
            return parseToolArguments(response, verified)
        } finally {
            connection.disconnect()
        }
    }

    private fun toolSchema() = JSONObject()
        .put("type", "function")
        .put("function", JSONObject()
            .put("name", "generate_organizer")
            .put("description", "Choose supported organizer compartment count and joint clearance.")
            .put("parameters", JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("properties", JSONObject()
                    .put("compartments", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 4))
                    .put("clearance_mm", JSONObject().put("type", "number").put("minimum", 0).put("maximum", 1.5)))
                .put("required", org.json.JSONArray().put("compartments").put("clearance_mm"))))

    private fun buildUserPrompt(description: String, spec: OrganizerSpec, hasImage: Boolean) = """
        Current verified organizer dimensions: width ${spec.widthMm} mm, depth ${spec.depthMm} mm,
        height ${spec.heightMm} mm, material ${spec.materialThicknessMm} mm. Preserve these values.
        The deterministic Origami V1 engine supports 1–4 compartments and 0–1.5 mm joint clearance.
        ${if (hasImage) "Use the attached photo only as context for the requested organizer; do not infer or change its verified dimensions." else ""}
        User request: ${description.take(1200)}
        Choose only supported values. Do not claim structural certification or invent other features.
    """.trimIndent()

    internal fun buildUserContent(description: String, spec: OrganizerSpec, imageDataUrl: String?): Any {
        val prompt = buildUserPrompt(description, spec, imageDataUrl != null)
        if (imageDataUrl == null) return prompt
        return JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", imageDataUrl)))
    }

    companion object {
        const val DEFAULT_ENDPOINT = "http://127.0.0.1:8080"
        private const val SYSTEM_PROMPT = "Return the required generate_organizer tool call. Never return CAD code."

        internal fun parseToolArguments(response: String, verified: OrganizerSpec): OrganizerSpec {
            val root = JSONObject(response)
            val calls = root.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getJSONArray("tool_calls")
            check(calls.length() == 1) { "MeshAI must return exactly one design tool call" }
            val call = calls.getJSONObject(0)
            check(call.getJSONObject("function").getString("name") == "generate_organizer") {
                "MeshAI returned an unsupported tool"
            }
            val args = JSONObject(call.getJSONObject("function").getString("arguments"))
            check(args.length() == 2 && args.has("compartments") && args.has("clearance_mm")) {
                "MeshAI returned unsupported design fields"
            }
            val compartments = args.get("compartments")
            check(compartments is Int || compartments is Long) { "Compartment count must be an integer" }
            val proposed = verified.copy(
                compartments = args.getInt("compartments"),
                clearanceMm = args.getDouble("clearance_mm"),
            )
            proposed.validate()
            return proposed
        }
    }
}

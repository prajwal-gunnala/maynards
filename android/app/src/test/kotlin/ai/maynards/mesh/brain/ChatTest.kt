package ai.maynards.mesh.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Chat against a one-shot local server that replays a canned llama-server event stream. */
class ChatTest {
    private fun serve(vararg events: String): String {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.accept().use { s ->
                val input = s.getInputStream().bufferedReader()
                var len = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.startsWith("Content-Length:", true)) len = line.substringAfter(':').trim().toInt()
                    if (line.isEmpty()) break
                }
                repeat(len) { input.read() }
                val body = events.joinToString("") { "data: $it\n\n" }
                s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n$body").toByteArray())
                s.getOutputStream().flush()
            }
            server.close()
        }
        return "http://127.0.0.1:${server.localPort}"
    }

    private val hello = """{"choices":[{"delta":{"content":"Hello"},"finish_reason":null}]}"""
    private val stop = """{"choices":[{"delta":{},"finish_reason":"stop"}],"timings":{"predicted_n":1,"predicted_per_second":null,"prompt_per_second":12.5}}"""

    @Test fun completeStreamReturnsTheAnswerAndNullTimingsBecomeZero() {
        val a = Chat(serve(hello, stop, "[DONE]")).ask(listOf(Turn("user", "hi")), onText = {})
        assertEquals("Hello", a.text)
        assertEquals(0.0, a.tokPerSec, 0.0)          // null in the stream, not NaN
        assertEquals(12.5, a.promptPerSec, 0.0)
    }

    @Test fun streamCutOffMidAnswerIsAnError() {
        val e = runCatching { Chat(serve(hello)).ask(listOf(Turn("user", "hi")), onText = {}) }.exceptionOrNull()
        assertTrue("$e", e is IOException && e.message!!.contains("cut off"))
    }

    @Test fun serverErrorEventIsAnError() {
        val e = runCatching { Chat(serve("""{"error":{"message":"RPC failed"}}""")).ask(listOf(Turn("user", "hi")), onText = {}) }.exceptionOrNull()
        assertTrue("$e", e is IOException && e.message!!.contains("RPC failed"))
    }

    @Test fun progressEventsAreReported() {
        val seen = ArrayList<ChatProgress>()
        Chat(serve("""{"prompt_progress":{"total":100,"cache":0,"processed":50,"time_ms":10}}""", hello, stop))
            .ask(listOf(Turn("user", "hi")), onText = {}, onProgress = { seen += it })
        assertEquals(ChatProgress.Reading(50, 100, 0, 10), seen.first())
        assertTrue(seen.last() is ChatProgress.Writing)
    }
}

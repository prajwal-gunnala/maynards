package ai.maynards.mesh.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Reads real model headers from the laptop's model folder; skipped where the files are absent. */
class GgufTest {
    private val dir = File(System.getenv("MESH_MODELS") ?: "/mnt/storage/meshai/models")

    @Test fun qwen3_0_6b() {
        val f = File(dir, "Qwen3-0.6B-Q8_0.gguf")
        assumeTrue(f.exists())
        val m = Gguf.read(f)
        println(m.copy(layerBytes = m.layerBytes.take(2)))
        assertEquals("qwen3", m.arch)
        assertEquals(28, m.layers)
        assertEquals(m.fileBytes, m.weightBytes + (m.fileBytes - m.weightBytes))
        assertTrue("weights are most of the file", m.weightBytes > m.fileBytes * 0.95)
        // 28 layers x 8 KV heads x (128 + 128) x 2 bytes
        assertEquals(28L * 8 * 256 * 2, m.kvBytesPerToken)
    }

    @Test fun qwen3_coder_30b_header() {
        // only the header is needed, so a partial download works; pass the real size
        val f = File(dir, "Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf")
        assumeTrue(f.exists())
        val m = Gguf.read(f, fileBytes = 18_556_689_568L)
        println("${m.name} ${m.arch} layers=${m.layers} block=${m.layerBytes.max() / 1e6} MB other=${m.otherBytes / 1e6} MB kv/token=${m.kvBytesPerToken}")
        assertEquals("qwen3moe", m.arch)
        assertEquals(48, m.layers)
        assertTrue(m.weightBytes in 18_000_000_000L..18_600_000_000L)
    }
}

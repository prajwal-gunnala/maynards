package ai.maynards.mesh.brain

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/** The stored-layer names must be the hashes ggml-rpc asks for, or the phone never finds them. */
class LayerStoreTest {
    private val dir = File(System.getenv("MESH_MODELS") ?: "/mnt/storage/meshai/models")

    @Test fun fnv1a_matches_known_values() {
        val f = File.createTempFile("fnv", ".bin").apply { writeText("a"); deleteOnExit() }
        RandomAccessFile(f, "r").use {
            assertEquals("af63dc4c8601ec8c", "%016x".format(LayerStore.hash(it, 0, 1, ByteArray(16))))   // FNV-1a 64 of "a"
        }
    }

    @Test fun coder_30b_layer_40_hashes_like_the_phone_saw() {
        val f = File(dir, "Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf")
        assumeTrue(f.exists() && f.length() == 18_556_689_568L)
        val t = Gguf.tensors(f)
        val big = t.filter { it.name.startsWith("blk.") && it.size > LayerStore.HASH_THRESHOLD }
        assertEquals(144, big.size)                                    // 48 layers x 3 expert tensors
        val down = t.first { it.name == "blk.40.ffn_down_exps.weight" }
        assertEquals(15_390_720_160L, down.offset)
        assertEquals(113_246_208L, down.size)
        // names found in the phone's files/cache/rpc after a run that held layer 40
        RandomAccessFile(f, "r").use {
            assertEquals("94a16f7ad603732b", "%016x".format(LayerStore.hash(it, down.offset, down.size, ByteArray(1 shl 20))))
        }
    }
}

package ai.maynards.mesh.brain

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

data class StoreState(
    val model: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val working: Boolean = false,
    val bytes: Long = 0,      // everything in the store, all models
) {
    fun json(): JSONObject = JSONObject().put("model", model).put("done", done).put("total", total)
        .put("working", working).put("bytes", bytes)
}

/**
 * The phone's own copy of its layers, in the place the engine looks before asking the Host for them.
 *
 * When the Host sends a layer bigger than 10 MB it first sends only a hash (FNV-1a 64 of the bytes). If
 * `cache/rpc/<hash>` exists here, the phone loads it from its own storage and nothing crosses the cable.
 * Filling that folder from the model file already on this phone means any split starts fast, from the first run.
 */
class LayerStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "cache/rpc")     // LLAMA_CACHE=files/cache, the engine adds "rpc"
    private val index = File(ctx.filesDir, "cache/stored.json")   // model -> hashes, so a second pass is instant
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow(StoreState(bytes = size()))
    val state: StateFlow<StoreState> = _state

    /** Store every big layer of these models (files in [models]). Runs once; already stored layers are skipped. */
    fun fill(models: File, files: List<String>) {
        if (job?.isActive == true) return
        job = scope.launch {
            for (name in files) {
                val f = File(models, name)
                if (!f.canRead() || !isActive) continue
                runCatching { fillOne(f) }.onFailure { _state.value = _state.value.copy(working = false) }
            }
            _state.value = _state.value.copy(working = false, bytes = size())
        }
    }

    fun clear() {
        job?.cancel()
        dir.listFiles()?.forEach { it.delete() }
        index.delete()
        _state.value = StoreState()
    }

    private fun size(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    private fun fillOne(f: File) {
        val key = "${f.name}:${f.length()}"
        val known = runCatching { JSONObject(index.readText()) }.getOrElse { JSONObject() }
        val big = Gguf.tensors(f).filter { it.name.startsWith("blk.") && it.size > HASH_THRESHOLD }
        val hashes = known.optJSONArray(key)
        if (hashes != null && hashes.length() == big.size && (0 until hashes.length()).all { File(dir, hashes.getString(it)).length() > 0 }) {
            _state.value = StoreState(f.name, big.size, big.size, false, size())
            return
        }
        dir.mkdirs()
        val done = org.json.JSONArray()
        val buf = ByteArray(1 shl 20)
        RandomAccessFile(f, "r").use { raf ->
            big.forEachIndexed { i, t ->
                _state.value = StoreState(f.name, i, big.size, true, _state.value.bytes)
                val hash = "%016x".format(hash(raf, t.offset, t.size, buf))
                val out = File(dir, hash)
                if (out.length() != t.size) {
                    val tmp = File(dir, "$hash.part")
                    tmp.outputStream().use { o ->
                        raf.seek(t.offset)
                        var left = t.size
                        while (left > 0) {
                            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) error("model file ended early")
                            o.write(buf, 0, n); left -= n
                        }
                    }
                    tmp.renameTo(out)
                }
                done.put(hash)
                if (i % 8 == 0) _state.value = _state.value.copy(bytes = size())
            }
        }
        index.parentFile?.mkdirs()
        index.writeText(known.put(key, done).toString())
        _state.value = StoreState(f.name, big.size, big.size, false, size())
    }

    companion object {
        const val HASH_THRESHOLD = 10L * 1024 * 1024   // ggml-rpc sends only a hash for layers bigger than this

        /** FNV-1a 64, the hash ggml-rpc uses to name stored layers. */
        fun hash(raf: RandomAccessFile, offset: Long, size: Long, buf: ByteArray): Long {
            var h = -0x340d631b7bdddcdbL                 // 0xcbf29ce484222325
            raf.seek(offset)
            var left = size
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) error("model file ended early")
                for (k in 0 until n) { h = (h xor (buf[k].toLong() and 0xff)) * 0x100000001b3L }
                left -= n
            }
            return h
        }
    }
}

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
    val error: String = "",
) {
    fun json(): JSONObject = JSONObject().put("model", model).put("done", done).put("total", total)
        .put("working", working).put("bytes", bytes).put("error", error)
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
    @Synchronized
    fun fill(models: File, files: List<String>, layerRange: IntRange? = null): Job {
        job?.takeIf { it.isActive }?.let { return it }
        val first = files.firstOrNull().orEmpty()
        _state.value = StoreState(model = first, working = files.isNotEmpty(), bytes = size())
        return scope.launch {
            for (name in files) {
                val f = File(models, name)
                if (!f.canRead() || !isActive) continue
                runCatching { fillOne(f, layerRange) }.onFailure {
                    _state.value = _state.value.copy(working = false, error = it.message ?: it.javaClass.simpleName)
                }
            }
            _state.value = _state.value.copy(working = false, bytes = size())
        }.also { job = it }
    }

    fun clear() {
        job?.cancel()
        dir.listFiles()?.forEach { it.delete() }
        index.delete()
        _state.value = StoreState()
    }

    private fun size(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    /** Names (FNV-1a 64 hex) of every stored tensor, whether pre-filled here or cached by rpc-server as it arrived. */
    fun hashes(): List<String> = dir.listFiles()?.map { it.name }?.filter { it.length == 16 && it.all { c -> c in "0123456789abcdef" } }.orEmpty()

    /** Which layer ranges of which model files are fully stored here: file name -> ["8-31"] or ["all"]. */
    fun stored(): Map<String, List<String>> = runCatching {
        val j = JSONObject(index.readText())
        j.keys().asSequence().mapNotNull { key ->
            val parts = key.split(':')
            if (parts.size < 3) return@mapNotNull null
            val hashes = j.getJSONArray(key)
            val complete = (0 until hashes.length()).all { File(dir, hashes.getString(it)).length() > 0 }
            if (complete) parts[0] to parts[2] else null
        }.groupBy({ it.first }, { it.second })
    }.getOrDefault(emptyMap())

    private fun fillOne(f: File, layerRange: IntRange?) {
        android.util.Log.i("mesh-cache", "prepare ${f.name} (${f.length()} bytes) for the llama.cpp RPC tensor cache")
        val rangeKey = layerRange?.let { "${it.first}-${it.last}" } ?: "all"
        val key = "${f.name}:${f.length()}:$rangeKey"
        val known = runCatching { JSONObject(index.readText()) }.getOrElse { JSONObject() }
        val big = Gguf.tensors(f).filter { tensor ->
            val layer = tensor.name.removePrefix("blk.").substringBefore('.').toIntOrNull()
            tensor.name.startsWith("blk.") && tensor.size > HASH_THRESHOLD &&
                (layerRange == null || layer in layerRange)
        }
        val hashes = known.optJSONArray(key)
        if (hashes != null && hashes.length() == big.size && (0 until hashes.length()).all { File(dir, hashes.getString(it)).length() > 0 }) {
            android.util.Log.i("mesh-cache", "cache hit: ${big.size} large tensors already indexed for ${f.name}")
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
        android.util.Log.i("mesh-cache", "cache ready: ${big.size} large tensors, ${size()} bytes total")
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

/** How much of a planned layer range (inclusive) a device already stores, from ranges like "8-31" or "all". */
enum class Stored { ALL, PART, NONE }

fun storedCoverage(ranges: List<String>?, from: Int, to: Int): Stored {
    if (ranges.isNullOrEmpty()) return Stored.NONE
    if ("all" in ranges) return Stored.ALL
    val have = ranges.mapNotNull { r -> r.split('-').mapNotNull(String::toIntOrNull).takeIf { it.size == 2 }?.let { it[0]..it[1] } }
    val covered = (from..to).count { layer -> have.any { layer in it } }
    return when (covered) { to - from + 1 -> Stored.ALL; 0 -> Stored.NONE; else -> Stored.PART }
}

/**
 * Host side: for one model file, the hashes of each layer's big tensors (the ones ggml-rpc sends as a hash).
 * Computed once from the Host's own copy and saved, so "which layers does a Helper already have" is exact.
 */
class LayerIndex(ctx: Context) {
    private val dir = File(ctx.filesDir, "layer-index").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val _ready = MutableStateFlow<Map<String, Map<Int, List<String>>>>(emptyMap())
    /** model file name -> layer -> hashes, for indexes that are built. */
    val ready: StateFlow<Map<String, Map<Int, List<String>>>> = _ready

    /** Loads a saved index or builds it in the background; returns at once. */
    fun ensure(model: File) {
        val key = "${model.name}:${model.length()}"
        if (_ready.value.containsKey(model.name) || !busy.add(key)) return
        scope.launch {
            try {
                val saved = File(dir, "${model.name}.${model.length()}.json")
                val index: Map<Int, List<String>> = if (saved.isFile) parse(JSONObject(saved.readText())) else build(model).also { idx ->
                    saved.writeText(JSONObject(idx.mapKeys { "${it.key}" }.mapValues { org.json.JSONArray(it.value) }).toString())
                }
                _ready.value = _ready.value + (model.name to index)
            } catch (_: Exception) {
            } finally { busy.remove(key) }
        }
    }

    private fun parse(j: JSONObject): Map<Int, List<String>> =
        j.keys().asSequence().associate { k -> k.toInt() to j.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } } }

    private fun build(model: File): Map<Int, List<String>> {
        val big = Gguf.tensors(model).filter { it.name.startsWith("blk.") && it.size > LayerStore.HASH_THRESHOLD }
        val buf = ByteArray(1 shl 20)
        val out = HashMap<Int, MutableList<String>>()
        RandomAccessFile(model, "r").use { raf ->
            for (t in big) {
                val layer = t.name.removePrefix("blk.").substringBefore('.').toIntOrNull() ?: continue
                out.getOrPut(layer) { mutableListOf() } += "%016x".format(LayerStore.hash(raf, t.offset, t.size, buf))
            }
        }
        return out
    }
}

/** Layers in from..to (inclusive) whose big tensors are all among [have]; null when the index is not built yet. */
fun storedLayers(index: Map<Int, List<String>>?, have: Set<String>, from: Int, to: Int): Int? =
    index?.let { (from..to).count { layer -> it[layer]?.let { hs -> hs.isNotEmpty() && hs.all { h -> h in have } } == true } }

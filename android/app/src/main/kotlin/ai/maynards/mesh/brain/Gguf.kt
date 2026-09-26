package ai.maynards.mesh.brain

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

/** What the planner needs to know about a model file, read from its header only. */
data class ModelInfo(
    val file: String,
    val name: String,
    val arch: String,
    val fileBytes: Long,
    val layerBytes: List<Long>,      // weights of each repeating block, blk.0 … blk.N-1
    val otherBytes: Long,            // embeddings, output head, norms: these stay on the Host
    val kvBytesPerToken: Long,       // KV cache per token at 16-bit, all layers together
    val contextMax: Int,
) {
    val layers: Int get() = layerBytes.size
    val weightBytes: Long get() = layerBytes.sum() + otherBytes
}

/**
 * Reads a GGUF header: metadata, then the tensor table. Tensor sizes come from the gaps between
 * their data offsets, so no table of quantisation formats is needed.
 */
object Gguf {
    fun read(file: File, fileBytes: Long = file.length()): ModelInfo =
        file.inputStream().use { read(it, file.name, fileBytes) }

    /** Where each tensor's bytes are in the file: absolute offset and size. */
    fun tensors(file: File): List<Tensor> = file.inputStream().use { i ->
        val h = header(i, file.length())
        h.names.indices.map { Tensor(h.names[it], h.dataStart + h.offsets[it], exactSize(h.types[it], h.elements[it]) ?: h.sizes[it]) }
    }

    /** Bytes of a tensor from its type: elements per block and bytes per block (ggml's type table). */
    private val blocks = mapOf(
        0 to (1 to 4), 1 to (1 to 2), 2 to (32 to 18), 3 to (32 to 20), 6 to (32 to 22), 7 to (32 to 24), 8 to (32 to 34),
        9 to (32 to 36), 10 to (256 to 84), 11 to (256 to 110), 12 to (256 to 144), 13 to (256 to 176), 14 to (256 to 210),
        15 to (256 to 292), 16 to (256 to 66), 17 to (256 to 74), 18 to (256 to 98), 19 to (256 to 50), 20 to (32 to 18),
        21 to (256 to 110), 22 to (256 to 82), 23 to (256 to 136), 24 to (1 to 1), 25 to (1 to 2), 26 to (1 to 4),
        27 to (1 to 8), 28 to (1 to 8), 29 to (256 to 56), 30 to (1 to 2), 34 to (256 to 54), 35 to (256 to 66), 39 to (32 to 17),
    )

    private fun exactSize(type: Int, elements: Long): Long? = blocks[type]?.let { (n, b) -> elements / n * b }

    data class Tensor(val name: String, val offset: Long, val size: Long)

    private class Header(val meta: Map<String, Any>, val names: List<String>, val offsets: List<Long>, val sizes: LongArray, val dataStart: Long,
                         val types: List<Int>, val elements: List<Long>)

    private fun header(input: InputStream, fileBytes: Long): Header {
        val r = Reader(input)
        check(r.u32() == 0x46554747L) { "not a GGUF file" }          // "GGUF", little-endian
        val version = r.u32()
        check(version >= 2) { "GGUF v$version is too old" }
        val tensorCount = r.u64()
        val kvCount = r.u64()

        val meta = HashMap<String, Any>()
        repeat(kvCount.toInt()) {
            val key = r.str()
            meta[key] = r.value(r.u32().toInt(), keep = key.startsWith("general.") || !key.startsWith("tokenizer."))
        }

        val names = ArrayList<String>()
        val offsets = ArrayList<Long>()
        val types = ArrayList<Int>()
        val elements = ArrayList<Long>()
        repeat(tensorCount.toInt()) {
            names += r.str()
            val dims = r.u32().toInt()
            var n = 1L
            repeat(dims) { n *= r.u64() }
            elements += n
            types += r.u32().toInt()
            offsets += r.u64()
        }
        val align = (meta["general.alignment"] as? Number)?.toLong() ?: 32L
        val dataStart = (r.pos + align - 1) / align * align

        val order = offsets.indices.sortedBy { offsets[it] }
        val sizes = LongArray(names.size)
        order.forEachIndexed { k, i ->
            val end = if (k + 1 < order.size) offsets[order[k + 1]] else fileBytes - dataStart
            sizes[i] = end - offsets[i]
        }
        return Header(meta, names, offsets, sizes, dataStart, types, elements)
    }

    fun read(input: InputStream, fileName: String, fileBytes: Long): ModelInfo {
        val h = header(input, fileBytes)
        val (meta, names, sizes) = Triple(h.meta, h.names, h.sizes)

        val arch = meta["general.architecture"] as? String ?: "unknown"
        fun num(key: String): Long? = when (val v = meta["$arch.$key"]) {
            is Number -> v.toLong()
            is List<*> -> v.filterIsInstance<Number>().maxOfOrNull { it.toLong() }   // per-layer values
            else -> null
        }
        val nLayer = num("block_count")?.toInt() ?: 0
        val layerBytes = LongArray(nLayer)
        var other = 0L
        names.forEachIndexed { i, n ->
            val blk = if (n.startsWith("blk.")) n.substring(4).substringBefore('.').toIntOrNull() else null
            if (blk != null && blk < nLayer) layerBytes[blk] += sizes[i] else other += sizes[i]
        }

        val nHead = num("attention.head_count") ?: 1
        val nHeadKv = num("attention.head_count_kv") ?: nHead
        val embd = num("embedding_length") ?: 0
        val kDim = num("attention.key_length") ?: (embd / nHead)
        val vDim = num("attention.value_length") ?: kDim

        return ModelInfo(
            file = fileName,
            name = meta["general.name"] as? String ?: fileName.removeSuffix(".gguf"),
            arch = arch,
            fileBytes = fileBytes,
            layerBytes = layerBytes.toList(),
            otherBytes = other,
            kvBytesPerToken = nLayer * nHeadKv * (kDim + vDim) * 2,
            contextMax = num("context_length")?.toInt() ?: 4096,
        )
    }

    /** Little-endian reader that can skip the huge tokenizer arrays cheaply. */
    private class Reader(input: InputStream) {
        private val d = DataInputStream(BufferedInputStream(input, 1 shl 16))
        var pos = 0L
            private set

        fun u8(): Int = d.readUnsignedByte().also { pos += 1 }
        fun u32(): Long {
            val b = ByteArray(4); d.readFully(b); pos += 4
            return (b[0].toLong() and 0xff) or ((b[1].toLong() and 0xff) shl 8) or
                ((b[2].toLong() and 0xff) shl 16) or ((b[3].toLong() and 0xff) shl 24)
        }
        fun u64(): Long = u32() or (u32() shl 32)
        fun str(): String {
            val n = u64().toInt()
            val b = ByteArray(n); d.readFully(b); pos += n
            return String(b)
        }
        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val s = d.skip(left)
                if (s <= 0) { d.readByte(); left -= 1 } else left -= s
            }
            pos += n
        }

        /** Reads one metadata value. Arrays nobody needs (the tokenizer's) are skipped, not stored. */
        fun value(type: Int, keep: Boolean): Any = when (type) {
            0, 1, 7 -> u8()
            2, 3 -> { skip(2); 0 }
            4 -> u32()
            5 -> u32().toInt()
            6 -> java.lang.Float.intBitsToFloat(u32().toInt())
            8 -> str()
            9 -> {
                val t = u32().toInt()
                val n = u64()
                val width = mapOf(0 to 1L, 1 to 1L, 7 to 1L, 2 to 2L, 3 to 2L, 4 to 4L, 5 to 4L, 6 to 4L, 10 to 8L, 11 to 8L, 12 to 8L)[t]
                if (!keep && width != null) { skip(n * width); emptyList<Any>() }
                else List(n.toInt()) { value(t, keep) }.let { if (keep) it else emptyList() }
            }
            10, 11 -> u64()
            12 -> java.lang.Double.longBitsToDouble(u64())
            else -> throw EOFException("unknown GGUF value type $type")
        }
    }
}

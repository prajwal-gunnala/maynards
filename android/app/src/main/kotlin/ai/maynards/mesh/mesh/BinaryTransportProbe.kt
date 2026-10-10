package ai.maynards.mesh.mesh

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

data class BinaryProbeResult(val passed: Boolean, val bytesEachWay: Long, val elapsedMs: Long, val error: String = "") {
    val megabytesPerSecond: Double
        get() = if (elapsedMs <= 0) 0.0 else bytesEachWay * 2.0 / (1024 * 1024) / (elapsedMs / 1000.0)
}

/** Bidirectional large-payload integrity check over the same framed stream adapter used by RPC. */
object BinaryTransportProbe {
    private const val REQUEST = 0x4d425231 // MBR1
    private const val RESPONSE = 0x4d425232 // MBR2
    private const val FINISH = 0x4d425233 // MBR3
    private const val DEFAULT_BYTES = 8L * 1024 * 1024
    private const val BLOCK = 32 * 1024

    fun host(stream: DataChannelStream, bytesEachWay: Long = DEFAULT_BYTES): BinaryProbeResult = runCatching {
        require(bytesEachWay in 1..(256L * 1024 * 1024))
        val started = System.nanoTime()
        val out = DataOutputStream(BufferedOutputStream(stream.output, BLOCK))
        val input = DataInputStream(BufferedInputStream(stream.input, BLOCK))
        val firstDigest = MessageDigest.getInstance("SHA-256")
        out.writeInt(REQUEST); out.writeLong(bytesEachWay); out.flush()
        writePattern(out, firstDigest, bytesEachWay, 17)
        out.flush()
        val expectedFirst = firstDigest.digest()
        require(input.readInt() == RESPONSE) { "Helper sent an invalid binary probe response" }
        require(input.readLong() == bytesEachWay) { "Helper received a different payload length" }
        require(MessageDigest.isEqual(expectedFirst, ByteArray(32).also(input::readFully))) {
            "Helper's SHA-256 for Host → Helper payload did not match"
        }
        val backDigest = MessageDigest.getInstance("SHA-256")
        readAndHash(input, backDigest, bytesEachWay, 29)
        val backHash = backDigest.digest()
        out.writeInt(FINISH); out.writeBoolean(true); out.write(backHash); out.flush()
        require(input.readBoolean()) { "Helper did not verify the return payload" }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        BinaryProbeResult(true, bytesEachWay, elapsed)
    }.getOrElse { BinaryProbeResult(false, bytesEachWay, 0, it.message ?: "binary transport probe failed") }

    fun helper(stream: DataChannelStream): BinaryProbeResult = runCatching {
        val started = System.nanoTime()
        val input = DataInputStream(BufferedInputStream(stream.input, BLOCK))
        val out = DataOutputStream(BufferedOutputStream(stream.output, BLOCK))
        require(input.readInt() == REQUEST) { "Host sent an invalid binary probe request" }
        val size = input.readLong()
        require(size in 1..(256L * 1024 * 1024)) { "binary probe size is outside the allowed range" }
        val receivedDigest = MessageDigest.getInstance("SHA-256")
        readAndHash(input, receivedDigest, size, 17)
        val receivedHash = receivedDigest.digest()
        out.writeInt(RESPONSE); out.writeLong(size); out.write(receivedHash); out.flush()
        val returnDigest = MessageDigest.getInstance("SHA-256")
        writePattern(out, returnDigest, size, 29)
        out.flush()
        require(input.readInt() == FINISH) { "Host did not finish the binary integrity check" }
        val hostVerified = input.readBoolean()
        val hostHash = ByteArray(32).also(input::readFully)
        val passed = hostVerified && MessageDigest.isEqual(returnDigest.digest(), hostHash)
        out.writeBoolean(passed); out.flush()
        require(passed) { "Host did not verify Helper → Host payload" }
        BinaryProbeResult(true, size, (System.nanoTime() - started) / 1_000_000)
    }.getOrElse { BinaryProbeResult(false, 0, 0, it.message ?: "binary transport probe failed") }

    private fun writePattern(out: DataOutputStream, digest: MessageDigest, size: Long, seed: Int) {
        val buffer = ByteArray(BLOCK)
        var offset = 0L
        while (offset < size) {
            val count = minOf(buffer.size.toLong(), size - offset).toInt()
            for (i in 0 until count) buffer[i] = ((offset + i) * 31L + seed * 17L).toByte()
            digest.update(buffer, 0, count)
            out.write(buffer, 0, count)
            offset += count
        }
    }

    private fun readAndHash(input: DataInputStream, digest: MessageDigest, size: Long, seed: Int) {
        val buffer = ByteArray(BLOCK)
        var offset = 0L
        while (offset < size) {
            val count = minOf(buffer.size.toLong(), size - offset).toInt()
            input.readFully(buffer, 0, count)
            for (i in 0 until count) {
                val expected = ((offset + i) * 31L + seed * 17L).toByte()
                require(buffer[i] == expected) { "binary payload byte mismatch at offset ${offset + i}" }
            }
            digest.update(buffer, 0, count)
            offset += count
        }
    }
}

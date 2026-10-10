package ai.maynards.mesh.mesh

import android.util.Log
import org.webrtc.DataChannel
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Presents one ordered/reliable WebRTC DataChannel as a bounded byte stream.
 *
 * llama.cpp RPC speaks TCP. The remote transport uses one DataChannel per TCP
 * connection, so message boundaries are deliberately erased here. Writes are
 * chunked conservatively, buffered sends are throttled, and incoming buffering
 * is capped to avoid an unbounded allocation if the TCP consumer stalls.
 */
class DataChannelStream(
    private val channel: DataChannel,
    queueCapacity: Int = DEFAULT_QUEUE_CHUNKS,
    private val maxChunkBytes: Int = DEFAULT_CHUNK_BYTES,
    private val highWaterBytes: Long = DEFAULT_HIGH_WATER_BYTES,
) : Closeable {
    private sealed interface Item {
        data class Bytes(val value: ByteArray) : Item
        data object End : Item
        data class Failure(val cause: java.io.IOException) : Item
    }

    private val incoming = ArrayBlockingQueue<Item>(queueCapacity)
    private val closed = AtomicBoolean(false)
    private val terminalError = AtomicReference<java.io.IOException?>(null)
    private val readerOwner = AtomicReference<Thread?>(null)
    val isOpen: Boolean get() = !closed.get() && channel.state() == DataChannel.State.OPEN
    private val sendMonitor = Object()
    private val writeLock = Object()
    private var sentBytes = 0L
    private var ackedBytes = 0L
    private var receivedBytes = 0L
    private var consumedBytes = 0L
    private val listener = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {
            synchronized(sendMonitor) { sendMonitor.notifyAll() }
        }

        override fun onStateChange() {
            Log.i(TAG, "channel=${channel.label()} state=${channel.state()}")
            synchronized(sendMonitor) { sendMonitor.notifyAll() }
            if (channel.state() == DataChannel.State.CLOSED) finish(null)
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (closed.get()) return
            if (buffer.binary.not()) {
                finish(java.io.IOException("text data is not valid on an RPC byte stream"))
                return
            }
            val src = buffer.data
            if (src.remaining() < FRAME_HEADER_BYTES) {
                finish(java.io.IOException("truncated WebRTC byte-stream frame")); return
            }
            val kind = src.get().toInt() and 0xff
            val sequence = src.long
            when (kind) {
                FRAME_ACK -> {
                    if (src.hasRemaining()) { finish(java.io.IOException("invalid WebRTC acknowledgement frame")); return }
                    synchronized(sendMonitor) {
                        if (sequence < ackedBytes || sequence > sentBytes) {
                            finish(java.io.IOException("out-of-range WebRTC byte-stream acknowledgement")); return
                        }
                        ackedBytes = sequence
                        sendMonitor.notifyAll()
                    }
                }
                FRAME_DATA -> {
                    val bytes = ByteArray(src.remaining())
                    src.get(bytes)
                    synchronized(sendMonitor) {
                        if (sequence != receivedBytes) {
                            finish(java.io.IOException("WebRTC stream sequence mismatch (expected $receivedBytes, received $sequence)")); return
                        }
                        receivedBytes += bytes.size
                    }
                    if (bytes.isNotEmpty() && !incoming.offer(Item.Bytes(bytes))) {
                        finish(java.io.IOException("WebRTC receive buffer exceeded its configured bound"))
                    }
                }
                else -> finish(java.io.IOException("unknown WebRTC byte-stream frame type $kind"))
            }
        }
    }

    val input: InputStream = object : InputStream() {
        private var current = ByteArray(0)
        private var offset = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(dst: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || len > dst.size - off) throw IndexOutOfBoundsException()
            if (len == 0) return 0
            val currentReader = Thread.currentThread()
            val owner = readerOwner.get()
            if (owner == null) readerOwner.compareAndSet(null, currentReader)
            if (readerOwner.get() !== currentReader) throw java.io.IOException("WebRTC byte stream supports one reader")
            while (offset >= current.size) {
                val item = try { incoming.poll(100, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.IOException("interrupted while reading WebRTC stream", e)
                }
                if (item == null) {
                    if (!closed.get()) continue
                    terminalError.get()?.let { throw it }
                    return -1
                }
                when (item) {
                    is Item.Bytes -> { current = item.value; offset = 0 }
                    Item.End -> return -1
                    is Item.Failure -> throw item.cause
                }
            }
            val count = minOf(len, current.size - offset)
            current.copyInto(dst, off, offset, offset + count)
            offset += count
            consumedBytes += count
            sendAck(consumedBytes)
            return count
        }

        override fun close() = this@DataChannelStream.close()
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(src: ByteArray, off: Int, len: Int) {
            if (off < 0 || len < 0 || len > src.size - off) throw IndexOutOfBoundsException()
            if (closed.get()) throw java.io.IOException("WebRTC stream is closed")
            synchronized(writeLock) {
                var at = off
                val end = off + len
                while (at < end) {
                    val count = minOf(maxChunkBytes, end - at)
                    awaitSendCapacity(count.toLong())
                    val payload = ByteBuffer.allocate(FRAME_HEADER_BYTES + count).order(ByteOrder.BIG_ENDIAN)
                    payload.put(FRAME_DATA.toByte()).putLong(sentBytes)
                    payload.put(src, at, count)
                    payload.flip()
                    synchronized(sendMonitor) {
                        val accepted = channel.send(DataChannel.Buffer(payload, true))
                        if (!accepted) throw java.io.IOException("WebRTC DataChannel rejected an RPC message")
                        sentBytes += count // serialize with ACK processing to avoid a fast-ACK race
                    }
                    at += count
                }
            }
        }

        override fun flush() = Unit
        override fun close() = this@DataChannelStream.close()
    }

    data class Counters(val sent: Long, val received: Long)
    fun counters(): Counters = synchronized(sendMonitor) { Counters(sentBytes, receivedBytes) }

    init {
        require(queueCapacity > 0)
        require(maxChunkBytes in 1..DEFAULT_CHUNK_BYTES)
        require(highWaterBytes >= maxChunkBytes)
        channel.registerObserver(listener)
        if (channel.state() == DataChannel.State.CLOSED) finish(null)
    }

    private fun awaitSendCapacity(nextBytes: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CHANNEL_OPEN_TIMEOUT_MS)
        synchronized(sendMonitor) {
            while (!closed.get() && channel.state() == DataChannel.State.CONNECTING) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw java.io.IOException("WebRTC DataChannel did not open within ${CHANNEL_OPEN_TIMEOUT_MS} ms")
                try { TimeUnit.NANOSECONDS.timedWait(sendMonitor, minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100))) }
                catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.IOException("interrupted while waiting for WebRTC channel", e)
                }
            }
            while (!closed.get() && channel.state() == DataChannel.State.OPEN &&
                (channel.bufferedAmount() >= highWaterBytes || sentBytes - ackedBytes + nextBytes > WINDOW_BYTES)) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw java.io.IOException("WebRTC send window made no progress for ${CHANNEL_OPEN_TIMEOUT_MS} ms")
                try { TimeUnit.NANOSECONDS.timedWait(sendMonitor, minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100))) }
                catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.IOException("interrupted while waiting for WebRTC send capacity", e)
                }
            }
        }
        if (closed.get() || channel.state() != DataChannel.State.OPEN) throw EOFException("WebRTC DataChannel is not open")
    }

    private fun sendAck(consumed: Long) {
        val frame = ByteBuffer.allocate(FRAME_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        frame.put(FRAME_ACK.toByte()).putLong(consumed)
        frame.flip()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CHANNEL_OPEN_TIMEOUT_MS)
        synchronized(sendMonitor) {
            while (!closed.get() && channel.state() == DataChannel.State.OPEN && channel.bufferedAmount() >= highWaterBytes) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw java.io.IOException("WebRTC acknowledgement send stalled for ${CHANNEL_OPEN_TIMEOUT_MS} ms")
                try { TimeUnit.NANOSECONDS.timedWait(sendMonitor, minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100))) }
                catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.IOException("interrupted while acknowledging WebRTC stream bytes", e)
                }
            }
            if (closed.get() || channel.state() != DataChannel.State.OPEN || !channel.send(DataChannel.Buffer(frame, true))) {
                throw java.io.IOException("could not acknowledge consumed WebRTC stream bytes")
            }
        }
    }

    private fun finish(error: java.io.IOException?) {
        if (!closed.compareAndSet(false, true)) return
        if (error != null) Log.e(TAG, "channel=${channel.label()} stream failed: ${error.message}", error)
        terminalError.set(error)
        val terminal = if (error == null) Item.End else Item.Failure(error)
        if (!incoming.offer(terminal) && error != null) {
            // Surface failure even if an invalid peer exceeded the receive window.
            incoming.clear()
            incoming.offer(terminal)
        }
        synchronized(sendMonitor) { sendMonitor.notifyAll() }
        if (error != null && channel.state() != DataChannel.State.CLOSED) channel.close()
    }

    override fun close() {
        val counts = counters()
        Log.i(TAG, "stream=${channel.label()} closed sent=${counts.sent} received=${counts.received}")
        finish(null)
        channel.unregisterObserver()
        if (channel.state() != DataChannel.State.CLOSED) channel.close()
    }

    companion object {
        private const val TAG = "MeshRTC"
        const val DEFAULT_CHUNK_BYTES = 16 * 1024
        const val DEFAULT_QUEUE_CHUNKS = 256 // 4 MiB maximum queued inbound data per connection
        const val DEFAULT_HIGH_WATER_BYTES = 1024L * 1024L
        private const val FRAME_HEADER_BYTES = 9
        private const val FRAME_DATA = 0x44
        private const val FRAME_ACK = 0x41
        private const val WINDOW_BYTES = 1024L * 1024L
        private const val CHANNEL_OPEN_TIMEOUT_MS = 15_000L
    }
}

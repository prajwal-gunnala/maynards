package ai.maynards.mesh.mesh

import org.webrtc.DataChannel
import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Copies one accepted local TCP socket to/from a corresponding reliable DataChannel stream. */
class TcpDataChannelBridge(
    private val listener: ServerSocket,
    private val openStream: (Socket) -> DataChannelStream,
    private val onError: (Throwable) -> Unit = {},
) : Closeable {
    private val stopped = AtomicBoolean(false)
    private val pumps: ExecutorService = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "mesh-tcp-bridge-io").apply { isDaemon = true }
    }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val acceptThread = Thread({ acceptLoop() }, "mesh-tcp-bridge-accept").apply { isDaemon = true }

    fun start() { acceptThread.start() }

    private fun acceptLoop() {
        while (!stopped.get()) {
            val socket = runCatching { listener.accept() }.getOrElse {
                if (!stopped.get()) onError(it)
                return
            }
            socket.tcpNoDelay = true
            if (sockets.size >= MAX_CONNECTIONS) {
                runCatching { socket.close() }
                onError(IllegalStateException("RPC proxy reached its $MAX_CONNECTIONS concurrent connection limit"))
                continue
            }
            sockets += socket
            pumps.execute {
                var stream: DataChannelStream? = null
                try {
                    stream = openStream(socket)
                    bridge(socket, stream)
                } catch (t: Throwable) {
                    if (!stopped.get()) onError(t)
                    runCatching { socket.close() }
                    sockets -= socket
                    runCatching { stream?.close() }
                }
            }
        }
    }

    private fun bridge(socket: Socket, stream: DataChannelStream) {
        val done = AtomicBoolean(false)
        val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "mesh-rpc-connection-io").apply { isDaemon = true } }
        fun stopBridge() {
            if (done.compareAndSet(false, true)) {
                runCatching { socket.close() }
                runCatching { stream.close() }
                sockets -= socket
                io.shutdownNow()
            }
        }
        fun pump(direction: String, input: InputStream, output: OutputStream) {
            var transferred = 0L
            try {
                val buffer = ByteArray(DataChannelStream.DEFAULT_CHUNK_BYTES)
                while (!done.get()) {
                    val n = input.read(buffer)
                    if (n < 0) { Log.i(TAG, "RPC bridge $direction EOF after $transferred bytes"); break }
                    if (n > 0) { output.write(buffer, 0, n); output.flush(); transferred += n }
                }
            } catch (t: Throwable) {
                if (!stopped.get() && !done.get()) { Log.e(TAG, "RPC bridge $direction failed after $transferred bytes", t); onError(t) }
            } finally {
                stopBridge()
            }
        }
        io.execute { pump("tcp-to-webrtc", socket.getInputStream(), stream.output) }
        io.execute { pump("webrtc-to-tcp", stream.input, socket.getOutputStream()) }
        if (stopped.get()) stopBridge()
    }

        override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { listener.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        pumps.shutdownNow()
    }

    companion object {
        private const val TAG = "MeshRTC"
        private const val MAX_CONNECTIONS = 3
        /** Connects to an existing local service (Helper RPC) and tunnels that socket to a channel. */
        fun bridgeConnected(
            socket: Socket,
            stream: DataChannelStream,
            onError: (Throwable) -> Unit = {},
            onClosed: () -> Unit = {},
        ): Closeable {
            socket.tcpNoDelay = true
            val stopped = AtomicBoolean(false)
            val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "mesh-connected-bridge").apply { isDaemon = true } }
            fun stopBridge() {
                if (stopped.compareAndSet(false, true)) {
                    runCatching { socket.close() }
                    runCatching { stream.close() }
                    io.shutdownNow()
                    onClosed()
                }
            }
            fun pump(direction: String, input: InputStream, output: OutputStream) {
                var transferred = 0L
                try {
                    val buffer = ByteArray(DataChannelStream.DEFAULT_CHUNK_BYTES)
                    while (!stopped.get()) {
                        val n = input.read(buffer)
                        if (n < 0) { Log.i(TAG, "RPC helper bridge $direction EOF after $transferred bytes"); break }
                        if (n > 0) { output.write(buffer, 0, n); output.flush(); transferred += n }
                    }
                } catch (t: Throwable) {
                    if (!stopped.get()) { Log.e(TAG, "RPC helper bridge $direction failed after $transferred bytes", t); onError(t) }
                } finally {
                    stopBridge()
                }
            }
            io.execute { pump("tcp-to-webrtc", socket.getInputStream(), stream.output) }
            io.execute { pump("webrtc-to-tcp", stream.input, socket.getOutputStream()) }
            return Closeable { stopBridge() }
        }
    }

}

package ai.maynards.mesh.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** What the engine is doing right now. */
data class EngineState(
    val status: Status = Status.IDLE,
    val address: String = "",      // ip:port it listens on
    val log: List<String> = emptyList(),
) {
    enum class Status { IDLE, STARTING, RUNNING, FAILED }
}

/**
 * Runs llama.cpp as a child process. The binaries ship inside the APK as lib*.so,
 * because Android only allows running files installed as native libraries.
 */
class Engine(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val libDir = File(ctx.applicationInfo.nativeLibraryDir)
    private var proc: Process? = null

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    fun specs(): Specs = Specs.read(ctx)

    // One start or stop at a time. Two overlapping starts used to leave the first child running,
    // holding its port and several GB, and the second could not bind.
    private val gate = Mutex()

    /** Helper: hold layers for a Host. Tries a few ports because Android sometimes reserves one. */
    fun startHelper(bind: String, threads: Int, backend: String = "cpu", ports: List<Int> = HELPER_PORTS) = scope.launch { gate.withLock {
        stop()
        val rpcBinary = if (backend.lowercase() == "opencl" && File(libDir, "libmesh_rpc_opencl.so").exists()) {
            "libmesh_rpc_opencl.so"
        } else {
            "libmesh_rpc.so"
        }
        for (port in ports) {
            val cmd = listOf(bin(rpcBinary), "-H", bind, "-p", "$port", "-t", "$threads", "-c")
            if (launch(cmd) && waitListening(bind, port)) {
                update { it.copy(status = EngineState.Status.RUNNING, address = "$bind:$port") }
                return@launch
            }
            stop()
        }
        update { it.copy(status = EngineState.Status.FAILED) }
    } }

    /** Host: load the model and serve the OpenAI-compatible API. */
    fun startHost(args: List<String>, bind: String, port: Int) = scope.launch { gate.withLock {
        stop()
        val cmd = listOf(bin("libmesh_server.so")) + args + listOf("--host", bind, "--port", "$port")
        if (launch(cmd)) update { it.copy(status = EngineState.Status.RUNNING, address = "$bind:$port") }
        else update { it.copy(status = EngineState.Status.FAILED) }
    } }

    fun stop() {
        proc?.let { p ->
            p.destroy()
            // wait after the forced kill too: the port is only free once the child is reaped,
            // and the next start used to race a dying process and fail to bind
            if (!p.waitForExitSafely(2000)) { p.destroyForcibly(); p.waitForExitSafely(2000) }
        }
        proc = null
        update { it.copy(status = EngineState.Status.IDLE, address = "") }
    }

    /** Memory the engine process really holds, in bytes. */
    fun heldBytes(): Long {
        val pid = proc?.let { pidOf(it) } ?: return 0
        return runCatching {
            File("/proc/$pid/status").readLines()
                .firstOrNull { it.startsWith("VmRSS:") }
                ?.filter { it.isDigit() }?.toLong()?.times(1024) ?: 0
        }.getOrDefault(0)
    }

    private fun launch(cmd: List<String>): Boolean {
        log("$ ${cmd.joinToString(" ") { it.substringAfterLast('/') }}")
        update { it.copy(status = EngineState.Status.STARTING) }
        return runCatching {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true).directory(ctx.filesDir)
            pb.environment()["LD_LIBRARY_PATH"] = libDir.path
            pb.environment()["HOME"] = ctx.filesDir.path
            pb.environment()["LLAMA_CACHE"] = File(ctx.filesDir, "cache").path  // helper's layer cache
            val p = pb.start()
            proc = p
            scope.launch {
                // reading stops with an exception when the process is destroyed; that is expected
                runCatching { p.inputStream.bufferedReader().forEachLine { log(it) } }
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                if (proc === p) {
                    log("exited with $code")
                    proc = null
                    update { it.copy(status = EngineState.Status.FAILED, address = "") }
                }
            }
            true
        }.getOrElse { log("start failed: ${it.message}"); false }
    }

    private suspend fun waitListening(host: String, port: Int): Boolean {
        repeat(30) {
            if (proc == null) return false
            val ok = runCatching { Socket().use { s -> s.connect(InetSocketAddress(host, port), 300) } }.isSuccess
            if (ok) return true
            delay(200)
        }
        return false
    }

    private fun bin(name: String) = File(libDir, name).path

    private fun log(line: String) = update { it.copy(log = (it.log + line).takeLast(60)) }

    private fun update(f: (EngineState) -> EngineState) {
        synchronized(this) { _state.value = f(_state.value) }
    }

    companion object {
        val HELPER_PORTS = listOf(50052, 50062, 50070, 50080, 50100)

        private val PID_IN_TOSTRING = Regex("pid=(\\d+)")

        /**
         * The process id, three ways. Reading the private ProcessImpl.pid field is blocked by
         * the non-SDK interface restrictions on recent Android, so heldBytes() returned 0 and
         * the Helper screen showed "Model memory 0.0 GB" while the phone held several GB.
         * Process.pid() exists from API 33 and is called reflectively so this still builds
         * against the older stub; toString() carries "pid=" on Android as the fallback.
         */
        private fun pidOf(p: Process): Long? =
            runCatching { Process::class.java.getMethod("pid").invoke(p) as? Long }.getOrNull()
                ?: PID_IN_TOSTRING.find(p.toString())?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: runCatching {
                    p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p).toLong()
                }.getOrNull()

        private fun Process.waitForExitSafely(ms: Long): Boolean = runCatching {
            waitFor(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
    }
}

package ai.maynards.mesh.brain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

data class Download(val file: String, val done: Long, val total: Long, val mbPerSec: Double, val error: String = "") {
    val fraction: Float get() = if (total > 0) done.toFloat() / total else 0f
}

/**
 * Pulls model files from a laptop over the private link (the laptop agent serves /models/<file>).
 * Writes to <file>.part and resumes from where it stopped; renames when complete.
 */
class Downloads(private val shelf: Shelf) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    private val _active = MutableStateFlow<Map<String, Download>>(emptyMap())
    val active: StateFlow<Map<String, Download>> = _active

    fun get(url: String, file: String, total: Long) {
        if (jobs[file]?.isActive == true) return
        jobs[file] = scope.launch {
            val part = File(shelf.dir, "$file.part")
            val t0 = System.nanoTime()
            val startAt = part.length()
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 30000
                if (startAt > 0) c.setRequestProperty("Range", "bytes=$startAt-")
                check(c.responseCode in 200..206) { "HTTP ${c.responseCode}" }
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(if (c.responseCode == 206) startAt else 0)
                    var done = out.filePointer
                    val buf = ByteArray(1 shl 20)
                    var lastUi = 0L
                    c.inputStream.use { inp ->
                        while (isActive) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.nanoTime()
                            if (now - lastUi > 500_000_000) {
                                lastUi = now
                                val mbs = (done - startAt) / 1e6 / ((now - t0) / 1e9)
                                _active.update { it + (file to Download(file, done, total, mbs)) }
                            }
                        }
                    }
                }
                if (part.length() >= total) {
                    part.renameTo(File(shelf.dir, file))
                    _active.update { it - file }
                }
            }.onFailure { e ->
                _active.update { it + (file to Download(file, part.length(), total, 0.0, e.message ?: "failed")) }
            }
        }
    }

    fun cancel(file: String) {
        jobs.remove(file)?.cancel()
        _active.update { it - file }
    }
}

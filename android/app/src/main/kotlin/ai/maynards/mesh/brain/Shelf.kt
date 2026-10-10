package ai.maynards.mesh.brain

import android.content.Context
import java.io.File

/**
 * The models on the Host phone. They live in the app's own folder, so no storage permission is needed:
 *   adb push model.gguf /sdcard/Android/data/ai.maynards.mesh/files/models/
 */
class Shelf(ctx: Context) {
    val dir: File = File(ctx.getExternalFilesDir(null), "models").apply { mkdirs() }
    private val cache = HashMap<String, ModelInfo>()

    /** Chat models only: projector and speech/image files are skipped. */
    fun scan(): List<ModelInfo> = dir.listFiles { f -> f.name.endsWith(".gguf") && !f.name.startsWith("mmproj") }
        .also { android.util.Log.i("mesh", "models in ${dir.path}: ${it?.map { f -> f.name }}") }
        .orEmpty()
        .mapNotNull { f ->
            val key = "${f.name}:${f.length()}"
            cache[key] ?: runCatching { Gguf.read(f) }
                .onFailure { android.util.Log.w("mesh", "cannot read ${f.name}: $it") }
                .getOrNull()?.also { cache[key] = it }
        }
        .filter { it.layers > 0 && it.arch !in NOT_CHAT }
        .sortedBy { it.fileBytes }

    fun path(m: ModelInfo) = File(dir, m.file).path

    /** The image projector that lets a vision model see, e.g. mmproj-Qwen2.5-VL-3B-Instruct-f16.gguf. */
    fun projector(m: ModelInfo): File? {
        val prefix = projectorPrefix(m.file)
        return dir.listFiles { f -> f.name.startsWith(prefix) }?.firstOrNull()
    }

    companion object {
        private val NOT_CHAT = setOf("clip", "qwen3tts", "qwen3asr", "whisper")
    }
}

/** Removes only a known quantization suffix, retaining names such as `-Instruct`. */
internal fun projectorPrefix(modelFile: String): String {
    val stem = modelFile.removeSuffix(".gguf")
    val base = stem.replace(Regex("-(?:Q[0-9].*|F16|BF16|F32)$", RegexOption.IGNORE_CASE), "")
    return "mmproj-$base"
}

package ai.maynards.mesh.brain

import android.content.Context
import ai.maynards.mesh.mesh.Secrets

/** Stable per-install bearer key used only by the local llama-server and paired clients. */
object LocalApiKey {
    @Synchronized
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences("mesh-host", Context.MODE_PRIVATE)
        return prefs.getString("local-model-api-key", null)?.takeIf { it.isNotBlank() }
            ?: Secrets.random(32).also { prefs.edit().putString("local-model-api-key", it).apply() }
    }
}

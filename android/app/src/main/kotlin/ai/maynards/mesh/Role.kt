package ai.maynards.mesh

import android.content.Context

/** Every phone runs the same app. The Host is the brain; Helpers lend their memory. */
enum class Role { HOST, HELPER }

object RoleStore {
    private const val PREFS = "mesh"
    private const val KEY = "role"

    fun load(ctx: Context): Role? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { runCatching { Role.valueOf(it) }.getOrNull() }

    fun save(ctx: Context, role: Role?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (role == null) remove(KEY) else putString(KEY, role.name)
        }.apply()
    }
}

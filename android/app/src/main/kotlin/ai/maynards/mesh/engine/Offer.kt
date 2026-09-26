package ai.maynards.mesh.engine

import android.content.Context

/**
 * How much of this phone's memory its owner is willing to lend to the mesh.
 *
 * Zero means "decide for me": everything free, less the reserve Android needs to stay responsive.
 * Anything else is a ceiling the device will not go above, whether it is hosting or helping, and it
 * is applied where the specs are read so every planner sees the same number without knowing about it.
 */
object Offer {
    private const val KEY = "offer_bytes"

    /** The choices worth offering, in GB. Anything larger than the device is filtered out by the UI. */
    val choices = listOf(0L, 2L, 4L, 6L, 8L, 12L, 16L)

    fun bytes(ctx: Context): Long =
        ctx.getSharedPreferences("mesh", Context.MODE_PRIVATE).getLong(KEY, 0L)

    fun set(ctx: Context, gb: Long) =
        ctx.getSharedPreferences("mesh", Context.MODE_PRIVATE).edit()
            .putLong(KEY, if (gb <= 0) 0L else gb * 1_000_000_000L).apply()

    fun gb(ctx: Context): Long = bytes(ctx) / 1_000_000_000L
}

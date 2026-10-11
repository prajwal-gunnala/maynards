package ai.maynards.mesh.brain

import java.util.Locale

/**
 * The last check before a model starts. A plan can be minutes old: a Helper may have left, its link or battery
 * may have dropped, free memory may have shrunk, or the model may now fit on the Host alone. The file on disk
 * must still be the one the plan was made from.
 *
 * Hard stops: the model file is missing or has a different size. Everything else is re-planned with the
 * devices as they are now; if no plan fits, it stops and says why.
 */
object Preflight {
    sealed class Result {
        data class Go(val plan: Plan, val note: String? = null) : Result()
        data class Stop(val reason: String) : Result()
    }

    fun check(plan: Plan, devices: List<Device>, fileBytesOnDisk: Long?, hostExtra: Long = 0, ctx: Int = 4096): Result {
        val m = plan.model
        if (fileBytesOnDisk == null) return Result.Stop("${m.name}: model file is missing")
        if (fileBytesOnDisk != m.fileBytes)
            return Result.Stop("${m.name}: model file changed (${gb(fileBytesOnDisk)} GB on disk, ${gb(m.fileBytes)} GB planned)")
        val host = devices.firstOrNull { it.isHost } ?: return Result.Stop("No Host device")
        if (plan.slices.isEmpty()) return Result.Stop(plan.reason)

        val byId = devices.associateBy { it.id }
        val problems = ArrayList<String>()
        for (s in plan.helpers) {
            val d = byId[s.deviceId]
            when {
                d == null -> problems += "${s.name} left"
                d.rttMs > Planner.MAX_RTT_MS -> problems += "${s.name} link slow (%.0f ms)".format(Locale.US, d.rttMs)
                !d.charging && d.battery < Planner.MIN_BATTERY -> problems += "${s.name} battery ${d.battery}%"
                d.usableBytes - Planner.HELPER_RESERVE < s.bytes ->
                    problems += "${s.name} has ${gb(d.usableBytes)} GB free, needs ${gb(s.bytes + Planner.HELPER_RESERVE)} GB"
            }
        }
        val hostNeed = plan.slices.first().bytes + Planner.HOST_RESERVE + hostExtra
        if (host.usableBytes < hostNeed)
            problems += "this phone has ${gb(host.usableBytes)} GB free, needs ${gb(hostNeed)} GB"
        val wholeNeed = m.weightBytes + Planner.kvBytes(m, ctx) + Planner.HOST_RESERVE + hostExtra
        if (plan.split && host.usableBytes >= wholeNeed) problems += "fits on this phone alone now"

        if (problems.isEmpty()) return Result.Go(plan)
        val again = Planner.plan(m, devices, ctx, hostExtra)
        return if (again.verdict != Verdict.NOT_POSSIBLE && again.slices.isNotEmpty())
            Result.Go(again, "Re-planned: ${problems.joinToString("; ")}")
        else Result.Stop("${problems.joinToString("; ")} · ${again.reason}")
    }

    private fun gb(b: Long) = "%.1f".format(Locale.US, b / 1e9)
}

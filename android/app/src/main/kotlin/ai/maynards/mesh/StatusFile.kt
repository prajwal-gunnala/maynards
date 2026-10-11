package ai.maynards.mesh

import ai.maynards.mesh.brain.Sense
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every 2 s, writes this phone's mesh state to files/status.json so a laptop panel (scripts/mesh-panel.py,
 * over adb) shows exactly what the app shows. Names, numbers and timings only: never prompt, document or
 * answer text. Written to a temp file and renamed, so a reader never sees half a file.
 */
object StatusFile {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(app: MeshApp) = scope.launch {
        val dir = app.getExternalFilesDir(null) ?: return@launch
        val out = File(dir, "status.json")
        val tmp = File(dir, "status.json.tmp")
        while (isActive) {
            runCatching { tmp.writeText(snapshot(app).toString()); tmp.renameTo(out) }
            delay(2_000)
        }
    }

    private fun snapshot(app: MeshApp): JSONObject {
        val j = JSONObject().put("at", System.currentTimeMillis()).put("phone", android.os.Build.MODEL)
        when (RoleStore.load(app)) {
            Role.HOST -> {
                val r = app.runner
                val run = r.state.value
                j.put("role", "host").put("status", run.status.name).put("step", run.step)
                    .put("model", run.plan?.model?.name).put("threads", r.threadsUsed).put("threads_from_profile", r.threadsFromProfile)
                j.put("split", JSONArray(run.plan?.slices.orEmpty().map {
                    JSONObject().put("name", it.name).put("from", it.from).put("to", it.to - 1).put("gb", it.bytes / 1e9) }))
                j.put("peers", JSONArray(app.host.peers.value.values.map { p ->
                    JSONObject().put("name", p.specs.name).put("link", p.linkKind).put("addr", p.addr).put("rtt_ms", p.rttMs)
                        .put("free_gb", p.specs.usableBytes / 1e9).put("battery", p.specs.battery)
                        .put("rpc_events", p.rpcSequence).put("rpc_age_ms", if (p.rpcEventAtMs > 0) System.currentTimeMillis() - p.rpcEventAtMs else -1)
                }))
                j.put("capsules", JSONArray(r.capsules.value.values.map { c ->
                    JSONObject().put("name", c.name).put("proposed", "layers ${c.capsule.from}–${c.capsule.to} · ${c.capsule.threads} threads")
                        .put("applied", c.applied?.let { "layers ${it.from}–${it.to} · ${it.threads} threads · file ${it.localFile}" }
                            ?: if (c.reported) "not reported" else "waiting")
                }))
                j.put("answers", JSONArray(app.stats.records.value.takeLast(8).map { s ->
                    JSONObject().put("at", s.at).put("task", s.task).put("model", s.model).put("layout", s.layout)
                        .put("tps", s.tokPerSec).put("prompt_tps", s.promptTokPerSec).put("first_ms", s.firstTokenMs)
                        .put("tokens", s.tokens).put("prompt_tokens", s.promptTokens).put("link", s.link)
                }))
                j.put("sense", JSONArray(Sense.events.value.takeLast(25).map { e ->
                    JSONObject().put("at", e.at).put("who", e.who).put("text", e.text).put("kind", e.kind.name).put("count", e.count)
                }))
            }
            Role.HELPER -> {
                val l = app.client.link.value
                val e = app.engine.state.value
                j.put("role", "helper").put("link", l.state.name).put("host", l.hostName).put("host_ip", l.hostIp)
                    .put("transport", l.transport).put("layers", l.layers).put("model", l.model)
                    .put("engine", e.status.name).put("rpc_events", e.rpcSequence)
                    .put("rpc_age_ms", if (e.rpcEventAtMs > 0) System.currentTimeMillis() - e.rpcEventAtMs else -1)
            }
            null -> j.put("role", "none")
        }
        return j
    }
}

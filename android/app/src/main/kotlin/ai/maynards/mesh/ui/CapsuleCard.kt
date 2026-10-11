package ai.maynards.mesh.ui

import ai.maynards.mesh.brain.Runner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp

/** Ned Capsules of the current run: what the Host proposed to each Helper, and what the Helper applied. */
@Composable
fun CapsuleCard(runner: Runner) {
    val caps by runner.capsules.collectAsState()
    if (caps.isEmpty()) return
    NBox(fill = Paper, pad = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("NED CAPSULES")
            caps.values.forEach { cs ->
                val c = cs.capsule
                val a = cs.applied
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Mono("${cs.name} · v${c.version} · ${c.id}", 11, Ink)
                    Mono("Proposed  layers ${c.from}–${c.to} · ${if (c.threads > 0) "${c.threads} threads" else "threads: Helper decides"} · ctx ${c.ctx} · ${c.backend}", 10)
                    when {
                        a != null -> {
                            val same = a.from == c.from && a.to == c.to && (c.threads <= 0 || a.threads == c.threads)
                            Mono("Applied   layers ${a.from}–${a.to} · ${a.threads} threads · ${a.backend} · model file ${a.localFile}",
                                10, if (same) Term else Warn)
                            a.notes.forEach { Mono("≠ $it", 10, Warn) }
                        }
                        cs.reported -> Mono("Applied   not reported (older Helper app)", 10, Warn)
                        else -> Mono("Applied   waiting for the Helper", 10)
                    }
                }
            }
        }
    }
}

package ai.maynards.mesh.ui

import ai.maynards.mesh.MeshService
import ai.maynards.mesh.engine.Engine
import ai.maynards.mesh.engine.EngineState.Status
import ai.maynards.mesh.engine.Net
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun HelperScreen(engine: Engine, onChangeRole: () -> Unit) {
    val ctx = LocalContext.current
    val s by engine.state.collectAsState()
    val running = s.status == Status.RUNNING
    val (sticker, color) = when (s.status) {
        Status.IDLE -> "off" to Paper
        Status.STARTING -> "starting" to Yellow
        Status.RUNNING -> "ready" to HostGreen
        Status.FAILED -> "failed" to Danger
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Header()
        NBox(fill = HelperPurple) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Title("Helper", 34)
                    Spacer(Modifier.width(10.dp))
                    Sticker(sticker, color)
                }
                Mono(if (running) s.address else Net.best()?.let { "${it.ip} · ${it.kind}" } ?: "no network")
            }
        }
        SpecsCard(rememberSpecs())
        if (running || s.status == Status.STARTING) {
            NButton("Stop", fill = Paper) { engine.stop(); MeshService.stop(ctx) }
        } else {
            NButton("Start helper", enabled = Net.best() != null) {
                val ip = Net.best()?.ip ?: return@NButton
                MeshService.start(ctx, "Helper ready")
                engine.startHelper(ip, threads = helperThreads())
            }
        }
        if (s.log.isNotEmpty()) {
            NBox(fill = Cream, shadow = 3.dp, pad = 10.dp) {
                Column { s.log.takeLast(6).forEach { Mono(it.take(80), 10, Muted) } }
            }
        }
        NButton("Change role", fill = Paper, onClick = onChangeRole)
    }
}

/** Leave two cores for Android and the UI. */
fun helperThreads() = (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(2)

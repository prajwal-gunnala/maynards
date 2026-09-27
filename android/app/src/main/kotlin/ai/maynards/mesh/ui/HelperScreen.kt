package ai.maynards.mesh.ui

import ai.maynards.mesh.MeshService
import ai.maynards.mesh.engine.Engine
import ai.maynards.mesh.engine.EngineState.Status
import ai.maynards.mesh.engine.gb
import ai.maynards.mesh.mesh.Invite
import ai.maynards.mesh.mesh.Link
import ai.maynards.mesh.mesh.MeshClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay

@Composable
fun HelperScreen(engine: Engine, client: MeshClient, onChangeRole: () -> Unit) {
    val ctx = LocalContext.current
    val link by client.link.collectAsState()
    val mesh by client.mesh.collectAsState()
    val eng by engine.state.collectAsState()
    val store by client.store.state.collectAsState()
    var scanError by remember { mutableStateOf("") }
    var held by remember { mutableLongStateOf(0L) }
    // a phone that joined before rejoins by itself (its secret is saved), no rescan needed
    LaunchedEffect(Unit) {
        if (link.state == Link.State.IDLE) client.savedInvite?.let { MeshService.start(ctx, "Helper joined"); client.join(it) }
    }
    LaunchedEffect(eng.status) {
        while (eng.status == Status.RUNNING) { held = engine.heldBytes(); delay(2000) }
        held = 0
    }

    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val invite = r.contents?.let(Invite::parse)
        if (invite == null) {
            scanError = if (r.contents == null) "" else "Not a MeshAI code"
            return@rememberLauncherForActivityResult
        }
        scanError = ""
        MeshService.start(ctx, "Helper joined")
        client.join(invite)
    }

    val (sticker, color) = when {
        eng.status == Status.RUNNING -> "working" to Yellow
        link.state == Link.State.JOINED -> "joined" to HostGreen
        link.state == Link.State.CONNECTING -> "connecting" to Tight
        link.state == Link.State.FAILED -> "refused" to Danger
        else -> "not joined" to Paper
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Header()
        NBox(fill = HelperPurple) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Title("Helper", 22)
                    Spacer(Modifier.width(10.dp))
                    Sticker(sticker, color)
                }
                if (link.state == Link.State.JOINED) {
                    IconLabel(Icons.Outlined.Link, "Host: ${link.hostName.ifBlank { "…" }} ${link.hostIp}")
                }
                if (link.state == Link.State.CONNECTING) IconLabel(Icons.Outlined.Link, "Connecting to the Host…")
                if (link.error.isNotBlank()) Mono(link.error, 11)
                if (scanError.isNotBlank()) Mono(scanError, 11)
            }
        }

        if (eng.status == Status.RUNNING) {
            NBox(fill = Yellow) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (link.model.isNotBlank()) Title(link.model, 18)
                    IconLabel(Icons.Outlined.Layers, "Holding layers ${link.layers.ifBlank { "…" }}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconLabel(Icons.Outlined.Memory, "Model memory")
                        Spacer(Modifier.weight(1f))
                        Text("${gb(held)} GB", fontWeight = FontWeight.Black, fontSize = 26.sp, color = Ink)
                    }
                    Mono(eng.address, 11)
                }
            }
        }

        if (link.state == Link.State.JOINED && mesh.devices.isNotEmpty()) {
            // the whole mesh, as the Host sees it
            NBox(fill = Paper) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Title("${mesh.devices.size} devices", 24)
                        Spacer(Modifier.width(10.dp))
                        Sticker(when (mesh.status) { "ready" -> "running"; "starting", "loading" -> "loading"; "failed" -> "stopped"; else -> "idle" },
                            when (mesh.status) { "ready" -> HostGreen; "starting", "loading" -> Tight; "failed" -> Danger; else -> Paper })
                    }
                    if (mesh.model.isNotBlank()) Mono(mesh.model + (mesh.tps?.let { " · last answer %.1f tok/s".format(it) } ?: ""), 12)
                    MeshWeb(
                        mesh.devices.map { d ->
                            WebNode(d.name.substringBefore(" (").take(14), if (d.role == "host") HostGreen else HelperPurple, isHost = d.role == "host",
                                note = listOfNotNull(d.layers.takeIf { it.isNotBlank() }?.let { "L$it" }, d.rttMs?.let { "%.0f ms".format(it) }.takeIf { d.role != "host" }).joinToString(" · "))
                        },
                        Modifier.fillMaxWidth().aspectRatio(1.3f),
                    )
                    mesh.devices.forEach { d ->
                        Mono((if (d.role == "host") "💻 " else "📱 ") + d.name + (if (d.layers.isNotBlank()) " · layers ${d.layers}" else ""), 11, Ink)
                    }
                }
            }
        } else {
            NBox(pad = 8.dp) {
                MeshWeb(listOf(WebNode("This phone", HelperPurple, isHost = true)), Modifier.fillMaxWidth().aspectRatio(1.6f))
            }
        }
        if (store.total > 0 || store.bytes > 0) {
            // layers kept on this phone: the Host sends only a hash for these, so starts are fast
            NBox(fill = Cream) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Meter(Icons.Outlined.Save, "Stored layers", "${gb(store.bytes)} GB",
                        if (store.total > 0) store.done.toFloat() / store.total else 1f, HostGreen)
                    if (store.total > 0) Mono("${store.model.removeSuffix(".gguf").take(34)} · ${store.done}/${store.total}" +
                        if (store.working) " · saving…" else if (store.done == store.total) " · ready" else "", 11, Ink)
                    NButton("Clear", fill = Paper) { client.store.clear() }
                }
            }
        }
        SpecsCard(rememberSpecs())

        when (link.state) {
            Link.State.IDLE, Link.State.FAILED -> NButton("Scan to join") {
                scan.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the Host's QR")
                        .setBeepEnabled(false).setOrientationLocked(false),
                )
            }
            else -> NButton("Leave", fill = Paper) { client.leave(); MeshService.stop(ctx) }
        }
        if (eng.log.isNotEmpty()) {
            NBox(fill = Cream, shadow = 3.dp, pad = 10.dp) {
                Column { eng.log.takeLast(5).forEach { Mono(it.take(80), 10, Muted) } }
            }
        }
        NButton("Change role", fill = Paper) { client.leave(); onChangeRole() }
    }
}

/** Leave two cores for Android and the UI. */
fun helperThreads() = (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(2)

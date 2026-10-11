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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background

@Composable
fun HelperScreen(engine: Engine, client: MeshClient, onChangeRole: () -> Unit) {
    val ctx = LocalContext.current
    val link by client.link.collectAsState()
    val mesh by client.mesh.collectAsState()
    val eng by engine.state.collectAsState()
    val store by client.store.state.collectAsState()
    var scanError by remember { mutableStateOf("") }
    var held by remember { mutableLongStateOf(0L) }
    var senseNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // a phone that joined before rejoins by itself (its secret is saved), no rescan needed
    LaunchedEffect(Unit) {
        if (link.state == Link.State.IDLE) client.savedInvite?.let { MeshService.start(ctx, "Helper joined"); client.join(it) }
    }
    LaunchedEffect(eng.status) {
        while (eng.status == Status.RUNNING) { held = engine.heldBytes(); delay(2000) }
        held = 0
    }
    LaunchedEffect(eng.rpcEventAtMs, eng.status) {
        while (eng.status == Status.RUNNING && eng.rpcEventAtMs > 0 &&
            System.currentTimeMillis() - eng.rpcEventAtMs < 2_000) {
            senseNow = System.currentTimeMillis()
            delay(200)
        }
        senseNow = System.currentTimeMillis()
    }
    val helperComputing = eng.rpcEventAtMs > 0 && senseNow - eng.rpcEventAtMs in 0..2_000

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
        if (link.state == Link.State.IDLE || link.state == Link.State.FAILED) {
            NBox(fill = HostGreen) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Term, RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(9.dp))
                        Label("Next")
                    }
                    Title("Scan to join", 22)
                    Text(
                        "Open MeshAI on the host phone, show its code, and point this camera at it. " +
                            "After that this phone rejoins on its own.",
                        fontSize = 14.sp, color = Muted, lineHeight = 19.sp,
                    )
                    NButton("Scan the code", fill = Term) {
                        scan.launch(
                            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the Host's QR")
                                .setBeepEnabled(false).setOrientationLocked(false),
                        )
                    }
                    if (scanError.isNotBlank()) Mono(scanError, 12, Bad)
                }
            }
        }
        PageHeader(
            "Helper",
            when (link.state) {
                Link.State.JOINED -> "This phone is lending its memory to the mesh. It holds part of the model and answers the host, once per word."
                Link.State.CONNECTING -> "Finding the host. It tries the cable first, because a cable is milliseconds and Wi-Fi is not."
                else -> "This phone is not in a mesh yet. Scan the code shown on the host and it will join, and rejoin by itself after a restart."
            },
        )
        NBox(fill = HelperPurple) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Title("Link", 16)          // the page is already titled Helper; this card is the link
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

        NBox(fill = Paper) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Title("Connection map", 20)
                    Spacer(Modifier.width(10.dp))
                    Sticker(
                        when {
                            link.state == Link.State.JOINED && helperComputing -> "computing"
                            link.state == Link.State.JOINED -> "joined"
                            link.state == Link.State.CONNECTING -> "connecting"
                            else -> "waiting"
                        },
                        when {
                            link.state == Link.State.JOINED && helperComputing -> Yellow
                            link.state == Link.State.JOINED -> HostGreen
                            link.state == Link.State.FAILED -> Danger
                            else -> Paper
                        },
                    )
                }
                Text(
                    when {
                        link.state == Link.State.JOINED -> "Host is at the center. This Helper is attached on a spoke."
                        link.state == Link.State.CONNECTING -> "Connecting this Helper to the Host."
                        else -> "This phone is the Helper. The Host appears at the center after pairing."
                    },
                    fontSize = 12.sp, lineHeight = 17.sp, color = Muted,
                )
                val route = when (link.transport) {
                    "wifi" -> "Wi-Fi · local TCP"
                    "usb" -> "USB tether · local TCP"
                    "hotspot" -> "Phone hotspot · local TCP"
                    "tailscale" -> "Tailscale · local TCP"
                    "WebRTC" -> "WebRTC DataChannel"
                    "TCP" -> "Local TCP"
                    else -> "Waiting for a link"
                }
                val hostNode = WebNode(
                    link.hostName.ifBlank { "Host" },
                    if (link.state == Link.State.JOINED) HostGreen else Faint,
                    isHost = true,
                    note = if (link.state == Link.State.JOINED) "host" else "waiting",
                )
                val helperNode = WebNode(
                    "This phone",
                    HelperPurple,
                    note = when {
                        helperComputing -> "RPC work"
                        link.state == Link.State.JOINED && link.layers.isNotBlank() -> "L${link.layers}"
                        link.state == Link.State.JOINED -> route.substringBefore(" ·")
                        else -> "Helper"
                    },
                )
                MeshWeb(
                    listOf(hostNode, helperNode),
                    Modifier.fillMaxWidth().aspectRatio(1.35f),
                    lineColor = if (link.state == Link.State.JOINED) Term.copy(alpha = 0.55f) else Muted.copy(alpha = 0.25f),
                    busy = helperComputing,
                )
                if (link.state == Link.State.JOINED) {
                    Mono("PATH · $route", 10, Term)
                    if (link.transport != "WebRTC" && link.localIp.isNotBlank()) {
                        Mono("THIS HELPER ${link.localIp}  →  HOST ${link.hostIp}", 10, Muted)
                    }
                    if (link.layers.isNotBlank()) Mono("ASSIGNED LAYERS · ${link.layers}", 10, Ink)
                    if (helperComputing) Mono("HELPER RPC · ${eng.rpcEvent} observed · event ${eng.rpcSequence}", 10, Term)
                } else {
                    Mono("No model work is flowing until the Host assigns layers.", 10, Muted)
                }
                if (mesh.devices.isNotEmpty() && mesh.devices.size > 2) {
                    Mono("Host reports ${mesh.devices.size} devices in this mesh.", 10, Muted)
                }
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
        if (eng.status == Status.RUNNING) {
            NBox(fill = if (eng.rpcSequence > 0) Yellow else Paper, pad = 11.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Label("THIS DEVICE'S WORK")
                    Mono(if (eng.rpcSequence > 0)
                        "Native RPC ${eng.rpcEvent} observed · event ${eng.rpcSequence}. This is the Helper's own engine log."
                    else "RPC server is listening. Waiting for a graph command from the Host.", 11)
                    Mono("Assigned layers ${link.layers.ifBlank { "not assigned" }} · ${link.model.ifBlank { "model not loaded" }}", 10, Muted)
                }
            }
        }
        SpecsCard(rememberSpecs())
        SpeedCard()

        if (link.state != Link.State.IDLE && link.state != Link.State.FAILED) {
            NButton("Leave the mesh", fill = Paper) { client.leave(); MeshService.stop(ctx) }
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

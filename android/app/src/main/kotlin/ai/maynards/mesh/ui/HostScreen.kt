package ai.maynards.mesh.ui

import ai.maynards.mesh.MeshService
import ai.maynards.mesh.engine.gb
import ai.maynards.mesh.engine.Net
import ai.maynards.mesh.mesh.MeshHost
import ai.maynards.mesh.mesh.Peer
import ai.maynards.origami.OrigamiTab
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.Memory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Mesh", Icons.Outlined.Hub),
    Tab("Models", Icons.Outlined.Inventory2),
    Tab("Use", Icons.Outlined.Chat),
    Tab("Stats", Icons.Outlined.BarChart),
)

@Composable
fun HostScreen(host: MeshHost, shelf: ai.maynards.mesh.brain.Shelf, runner: ai.maynards.mesh.brain.Runner, stats: ai.maynards.mesh.brain.Stats, downloads: ai.maynards.mesh.brain.Downloads, hostCap: Long = 0, autoRun: String? = null, autoRunHelpers: Int = 0, onChangeRole: () -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var useCase by rememberSaveable { mutableStateOf<String?>(null) }
    val peers by host.peers.collectAsState()
    val me = rememberSpecs()
    LaunchedEffect(Unit) {
        runCatching { host.start() }
        MeshService.start(ctx, "Host running")
        if (autoRun != null) {
            kotlinx.coroutines.delay(4_000)              // give Helpers a moment to rejoin
            kotlinx.coroutines.withTimeoutOrNull(60_000) {
                host.peers.first { it.size >= autoRunHelpers && it.values.all { p -> p.rttMs >= 0 } }
            }
            val m = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { shelf.scan() }
                .firstOrNull { it.file.contains(autoRun, ignoreCase = true) }
            if (m != null) {
                val p = ai.maynards.mesh.brain.Planner.plan(m, meshDevices(ai.maynards.mesh.engine.Specs.read(ctx), host.peers.value.values, hostCap),
                    hostExtra = shelf.projector(m)?.length() ?: 0,
                    preferSplit = autoRunHelpers > 0)
                if (p.verdict != ai.maynards.mesh.brain.Verdict.NOT_POSSIBLE) { runner.run(p); tab = 2 }
            }
        }
    }
    val runNow by runner.state.collectAsState()
    val ctxNow = LocalContext.current
    var online by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { online = ai.maynards.mesh.engine.Net.online(ctxNow); kotlinx.coroutines.delay(4_000) } }
    Column(Modifier.fillMaxSize()) {
        // the one line that says, from across a room, what this phone is doing (the chat page has its own header)
        if (tab != 2) StatusStrip(
            state = when (runNow.status) {
                ai.maynards.mesh.brain.RunState.Status.READY -> "ready"
                ai.maynards.mesh.brain.RunState.Status.LOADING -> "loading"
                ai.maynards.mesh.brain.RunState.Status.STARTING -> "starting"
                ai.maynards.mesh.brain.RunState.Status.FAILED -> "stopped"
                else -> "idle"
            },
            stateColor = when (runNow.status) {
                ai.maynards.mesh.brain.RunState.Status.READY -> Term
                ai.maynards.mesh.brain.RunState.Status.FAILED -> Bad
                ai.maynards.mesh.brain.RunState.Status.IDLE -> Faint
                else -> Warn
            },
            model = runNow.plan?.model?.name,
            devices = 1 + peers.size,
            layers = runNow.plan?.slices?.firstOrNull()?.takeIf { it.to > it.from }?.let { "layers ${it.from}-${it.to - 1} here" },
            offline = !online,
            tps = null,
        )
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> MeshTab(host, runner, onChangeRole, onGo = { tab = it })
                1 -> ModelsTab(shelf, meshDevices(me, peers.values, hostCap), onRun = { runner.run(it); tab = 2 },
                    peers = peers.values, downloads = downloads)
                2 -> when (useCase) {
                    null -> UseHome(onChoose = { useCase = it })
                    "origami" -> Column(Modifier.fillMaxSize()) {
                        NButton("← Use cases", fill = Paper) { useCase = null }
                        OrigamiTab(aiEndpoint = runner.endpoint, aiApiKey = runner.apiKey, prepareAi = { needsVision -> ensureUseCaseModel(
                            needsVision, shelf, runner, meshDevices(me, peers.values, hostCap))
                        })
                    }
                    "case" -> Column(Modifier.fillMaxSize()) {
                        NButton("← Use cases", fill = Paper) { useCase = null }
                        PrivateUseTab(runner, peers.values, onEnsureModel = {
                            ensureUseCaseModel(false, shelf, runner, meshDevices(me, peers.values, hostCap))
                        }, onAnswer = { a -> runner.state.value.plan?.let { stats.add(it, a, me.heat, peers.values, me.usableBytes, "case") } })
                    }
                    "developer" -> Column(Modifier.fillMaxSize()) {
                        NButton("← Use cases", fill = Paper) { useCase = null }
                        DeveloperTab(runner, onEnsureModel = {
                            ensureUseCaseModel(false, shelf, runner, meshDevices(me, peers.values, hostCap))
                        }, onAnswer = { a -> runner.state.value.plan?.let { stats.add(it, a, me.heat, peers.values, me.usableBytes, "code") } })
                    }
                    else -> {
                        val run by runner.state.collectAsState()
                        Column(Modifier.fillMaxSize()) {
                            NButton("← Use cases", fill = Paper) { useCase = null }
                            UseTab(
                                runner, host, canSee = run.plan?.model?.let { shelf.projector(it) != null } == true, onPickModel = { tab = 1 },
                                system = { meshNote(run.plan, me, peers.values) },
                            ) { a ->
                                run.plan?.let { stats.add(it, a, me.heat, peers.values, me.usableBytes) }
                                runner.record(a)
                            }
                        }
                    }
                }
                3 -> StatsTab(stats)
                else -> Soon(TABS[tab])
            }
        }
        NavBar(TABS.map { it.label to it.icon }, tab) { tab = it }
    }
}

@Composable
private fun MeshTab(host: MeshHost, runner: ai.maynards.mesh.brain.Runner, onChangeRole: () -> Unit,
                    onGo: (Int) -> Unit) {
    val context = LocalContext.current
    val run by runner.state.collectAsState()
    val peers by host.peers.collectAsState()
    val metrics by runner.metrics.collectAsState()
    val token by host.token.collectAsState()
    val remoteInvite by host.remoteInvite.collectAsState()
    val remoteState by host.remoteState.collectAsState()
    val remoteRoute by host.remoteRoute.collectAsState()
    val me = rememberSpecs()
    var senseNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            senseNow = System.currentTimeMillis()
            kotlinx.coroutines.delay(500)
        }
    }
    val helperGraphRecentlyObserved = run.plan?.helpers.orEmpty().any { slice ->
        val eventAt = peers[slice.deviceId]?.rpcEventAtMs ?: 0L
        eventAt > 0 && senseNow - eventAt in 0..2_000
    }
    var showQr by remember { mutableStateOf(host.peers.value.isEmpty()) }   // once someone joined, the web comes first
    var showRemoteQr by remember { mutableStateOf(false) }
    var publicUrl by rememberSaveable {
        mutableStateOf(context.getSharedPreferences("mesh-host", android.content.Context.MODE_PRIVATE)
            .getString("remote-service-url", "") ?: "")
    }
    var forceRelay by rememberSaveable { mutableStateOf(false) }
    var remoteError by remember { mutableStateOf("") }
    val remoteScope = rememberCoroutineScope()
    var localRoutes by remember { mutableStateOf(Net.addresses()) }
    var selectedLocalIp by rememberSaveable { mutableStateOf("") } // blank is the explicit Auto option
    LaunchedEffect(Unit) {
        while (true) {
            localRoutes = Net.addresses()
            kotlinx.coroutines.delay(2_000)
        }
    }
    LaunchedEffect(localRoutes, selectedLocalIp) {
        if (selectedLocalIp.isNotBlank() && localRoutes.none { it.ip == selectedLocalIp }) selectedLocalIp = ""
    }
    val localInvite = host.invite(selectedLocalIp.takeIf { it.isNotBlank() })

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // while a model runs, free memory shrinks by what the layers took: count what the devices hold, plus what is still free
        val live = run.plan?.takeIf { run.status != ai.maynards.mesh.brain.RunState.Status.IDLE && run.status != ai.maynards.mesh.brain.RunState.Status.FAILED }
        val holding = live?.slices?.sumOf { it.bytes } ?: 0L
        val pool = holding + me.usableBytes + peers.values.sumOf { it.specs.usableBytes }
        NextStep(run, peers.size, onGo) { showQr = true }
        PageHeader(
            "Mesh",
            if (peers.isEmpty()) "This phone is the host. Add a device and their memory is pooled, so a model too big for either can run across both."
            else "${peers.size + 1} devices, ${gb(pool)} GB pooled. The host plans which device holds which layers.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(Icons.Outlined.Hub, "Devices", "${peers.size + 1}", if (peers.isEmpty()) "alone" else "joined", Modifier.weight(1f))
            StatTile(Icons.Outlined.Memory, "Pooled", gb(pool), "GB", Modifier.weight(1f))
        }
        if (run.status != ai.maynards.mesh.brain.RunState.Status.IDLE) {
            LiveMetricsCard(run, metrics, peers.values)
        }
        // the split of the model that is running: which device holds which layers, and how many GB
        live?.let { plan ->
            NBox(pad = 12.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("${plan.model.name} · split")
                    LayerBar(plan)
                }
            }
        }
        NBox(pad = 8.dp) {
            MeshWeb(
                listOf(WebNode("This phone", HostGreen, isHost = true, note = layersOf(run.plan, me.id))) +
                    peers.values.map { WebNode(it.specs.name, HelperPurple, note = layersOf(run.plan, it.id)) },
                Modifier.fillMaxWidth().aspectRatio(1.4f),
                busy = helperGraphRecentlyObserved,
            )
        }

        NBox(fill = Paper) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Label("Remote Internet link · WebRTC")
                OutlinedTextField(
                    value = publicUrl,
                    onValueChange = { publicUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://your-mesh-host.example") },
                    label = { Text("Signaling service URL") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Force TURN relay", Modifier.weight(1f), fontSize = 13.sp)
                    Switch(checked = forceRelay, onCheckedChange = { forceRelay = it })
                }
                if (remoteState != "idle") Mono(remoteState, 11)
                remoteRoute?.let { route ->
                    Mono("ICE ${if (route.relayed) "relay" else "direct"} · ${route.localType} → ${route.remoteType}" +
                        (route.rttMs?.let { " · %.1f ms RTT".format(it) } ?: "") +
                        (route.jitterMs?.let { " · %.1f ms jitter".format(it) } ?: ""), 11, Term)
                }
                host.remoteProbe.collectAsState().value?.let { probe ->
                    Mono(if (probe.passed) "8 MiB each direction · SHA-256 verified · %.2f MiB/s".format(probe.megabytesPerSecond)
                        else "Binary test failed: ${probe.error}", 11, if (probe.passed) Term else Bad)
                }
                if (remoteError.isNotBlank()) Mono(remoteError, 11, Bad)
                NButton(if (remoteInvite == null) "Create WebRTC invite" else "Create a fresh WebRTC invite", fill = Term) {
                    remoteError = ""
                    remoteScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                context.getSharedPreferences("mesh-host", android.content.Context.MODE_PRIVATE).edit()
                                    .putString("remote-service-url", publicUrl.trim()).apply()
                                host.createRemoteInvite(publicUrl.trim(), forceRelay)
                            }
                        }.onSuccess { showRemoteQr = true }
                            .onFailure { remoteError = it.message ?: "Could not create remote session" }
                    }
                }
            }
        }

        if (showQr || showRemoteQr) {
            NBox(fill = Yellow) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconLabel(Icons.Outlined.QrCode2, if (showRemoteQr && remoteInvite != null) "Scan for WebRTC remote pairing" else "Scan with a Helper")
                    if (!showRemoteQr) {
                        Text("QR route", fontSize = 12.sp, color = Muted)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            RouteChoice("Auto", selectedLocalIp.isBlank()) { selectedLocalIp = "" }
                            localRoutes.forEach { address ->
                                RouteChoice("${address.kind.uppercase()} · ${address.ip}" + if (address.kind == "usb") " · laptop only" else "", selectedLocalIp == address.ip) {
                                    selectedLocalIp = address.ip
                                }
                            }
                        }
                        Text(
                            if (selectedLocalIp.isBlank()) "Auto advertises available routes in preference order."
                            else "This QR advertises only ${selectedLocalIp}; the Helper will not silently switch routes.",
                            fontSize = 11.sp, color = Muted,
                        )
                    }
                    Box(Modifier.background(Paper, RoundedCornerShape(8.dp)).border(Border, Ink, RoundedCornerShape(8.dp)).padding(10.dp)) {
                        val inviteJson = if (showRemoteQr) remoteInvite?.toJson() ?: localInvite.toJson() else localInvite.toJson()
                        QrImage(remember(inviteJson, token, peers.size) { inviteJson }, Modifier.size(220.dp))
                    }
                    val qrRemoteInvite = remoteInvite
                    if (showRemoteQr && qrRemoteInvite != null) Mono("${qrRemoteInvite.remoteUrl} · expires in 10 minutes", 11)
                    else Mono(localInvite.hosts.joinToString("  ").ifBlank { "No compatible local route available" }, 11)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NButton(if (showQr) "Hide local QR" else "Add locally", modifier = Modifier.weight(1f), fill = if (showQr) Paper else Term) {
                showQr = !showQr; showRemoteQr = false
            }
            if (showRemoteQr) NButton("Hide remote QR", modifier = Modifier.weight(1f), fill = Paper) { showRemoteQr = false }
        }

        Section("Devices")
        DeviceRow(me.name, "this phone · host", me.usableBytes, me.heat, me.battery, me.charging, -1.0, HostGreen, false,
            holding = live?.slices?.firstOrNull())
        peers.values.sortedBy { it.specs.name }.forEach { p -> PeerRow(p, live?.slices?.firstOrNull { it.deviceId == p.id }) }
        if (peers.isEmpty()) NBox {
            EmptyState(
                "No other device yet",
                "On the other phone open MeshAI, choose Helper, and scan the code above. A laptop joins from its terminal with mesh join.",
            )
        }

        NButton("Change role", fill = Paper, onClick = onChangeRole)
    }
}

@Composable
private fun RouteChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) Term else Paper, RoundedCornerShape(18.dp))
            .border(1.dp, if (selected) Term else Ink, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) Paper else Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A short note for the model about where it is running, so "what am I running on?" gets a real answer.
 * Only this mesh's own device specs go in; nothing leaves the devices.
 */
private fun meshNote(plan: ai.maynards.mesh.brain.Plan?, me: ai.maynards.mesh.engine.Specs, peers: Collection<Peer>): String {
    fun line(name: String, s: ai.maynards.mesh.engine.Specs, id: String) = buildString {
        append("- $name: ${s.chip}, ${s.cores} cores up to ${"%.1f".format(s.maxGhz)} GHz, ")
        append("${gb(s.totalBytes)} GB RAM (${gb(s.freeBytes)} GB free), battery ${s.battery}%${if (s.charging) " charging" else ""}")
        plan?.slices?.firstOrNull { it.deviceId == id }?.let { append(", holding layers ${it.from}-${it.to - 1} (${gb(it.bytes)} GB)") }
    }
    return buildString {
        appendLine("You are ${plan?.model?.name ?: "a local model"}, served by llama-server on the Host phone. MeshAI does not route this request to a cloud model API.")
        appendLine("The model is ${plan?.reason?.lowercase() ?: "running"}. Devices:")
        appendLine(line("This phone (${me.name}, the Host)", me, me.id))
        peers.forEach { appendLine(line("${it.specs.name} (Helper, ${it.linkKind} link, ${"%.0f".format(it.rttMs)} ms)", it.specs, it.id)) }
        appendLine("A Helper assigned layers receives intermediate activations to compute those layers. Do not claim those activations contain no recoverable user information.")
        append("Answer briefly. Use these facts when asked about the devices or where you run.")
    }
}

/** "25 layers" for a device in the running plan, or nothing. */
private fun layersOf(plan: ai.maynards.mesh.brain.Plan?, id: String): String =
    plan?.slices?.firstOrNull { it.deviceId == id }?.let { "${it.count} layers" } ?: ""

/** Small, live dashboard for the Host. It contains only numbers produced on these phones. */
@Composable
private fun LiveMetricsCard(
    run: ai.maynards.mesh.brain.RunState,
    metrics: ai.maynards.mesh.brain.LiveMetrics,
    peers: Collection<Peer>,
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(metrics.startedAtMs, run.status) {
        while (run.status == ai.maynards.mesh.brain.RunState.Status.LOADING ||
            run.status == ai.maynards.mesh.brain.RunState.Status.STARTING ||
            run.status == ai.maynards.mesh.brain.RunState.Status.READY) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val elapsed = if (metrics.startedAtMs > 0) ((now - metrics.startedAtMs) / 1_000).coerceAtLeast(0) else 0
    val link = peers.mapNotNull { it.rttMs.takeIf { ms -> ms >= 0 } }.minOrNull()
    NBox(pad = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("SPIDER SENSE · LIVE MESH METRICS")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCell("STATE", run.status.name.lowercase(), Modifier.weight(1f))
                MetricCell("UPTIME", "${elapsed}s", Modifier.weight(1f))
                MetricCell("LINK RTT", link?.let { "%.0f ms".format(it) } ?: "—", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCell("DECODE", metrics.lastTokPerSec.takeIf { it > 0 }?.let { "%.1f tok/s".format(it) } ?: "waiting", Modifier.weight(1f))
                MetricCell("PREFILL", metrics.lastPromptPerSec.takeIf { it > 0 }?.let { "%.1f tok/s".format(it) } ?: "—", Modifier.weight(1f))
                MetricCell("FIRST TOKEN", metrics.lastFirstTokenMs.takeIf { it >= 0 }?.let { "%.1f s".format(it / 1000.0) } ?: "—", Modifier.weight(1f))
            }
            val plan = run.plan
            Mono(
                "${plan?.slices?.size ?: 0} device${if ((plan?.slices?.size ?: 0) == 1) "" else "s"} assigned · " +
                    "${metrics.completedRequests} completed request${if (metrics.completedRequests == 1) "" else "s"} · " +
                    "last output ${metrics.lastTokens} tokens",
                10, Muted,
            )
            if (plan?.helpers?.isNotEmpty() == true) {
                peers.filter { peer -> plan.helpers.any { it.deviceId == peer.id } }.forEach { peer ->
                    val age = if (peer.rpcEventAtMs > 0) ((now - peer.rpcEventAtMs) / 1_000).coerceAtLeast(0) else -1
                    Mono("${peer.specs.name}: " + if (peer.rpcSequence > 0)
                        "native RPC ${peer.rpcEvent} observed #${peer.rpcSequence} · ${age}s ago"
                    else "waiting for native RPC graph activity evidence", 10, if (age in 0..2) Term else Muted)
                }
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Raised, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 7.dp)) {
        Mono(label, 8, Muted)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PeerRow(p: Peer, holding: ai.maynards.mesh.brain.Slice? = null) = DeviceRow(
    p.specs.name, "${p.linkKind} · ${p.addr} · helper" + if (p.rpcSequence > 0) " · RPC ${p.rpcEvent} #${p.rpcSequence} observed" else " · awaiting RPC event",
    p.specs.usableBytes, p.specs.heat, p.specs.battery, p.specs.charging,
    p.rttMs, HelperPurple, p.specs.kind == "laptop", holding,
)

/** Chooses the best installed model that fits the connected memory pool for the requested modality. */
private suspend fun ensureUseCaseModel(
    needsVision: Boolean,
    shelf: ai.maynards.mesh.brain.Shelf,
    runner: ai.maynards.mesh.brain.Runner,
    devices: List<ai.maynards.mesh.brain.Device>,
): String {
    val current = runner.state.value
    val currentModel = current.plan?.model
    val suitable = currentModel != null && (!needsVision || shelf.projector(currentModel) != null)
    if (current.status == ai.maynards.mesh.brain.RunState.Status.READY && suitable) return currentModel!!.name
    // A model already starting is waited for, not restarted: a restart throws away its load progress.
    if (suitable && (current.status == ai.maynards.mesh.brain.RunState.Status.STARTING ||
            current.status == ai.maynards.mesh.brain.RunState.Status.LOADING)) {
        val result = kotlinx.coroutines.withTimeout(10 * 60_000L) {
            runner.state.first { it.status == ai.maynards.mesh.brain.RunState.Status.READY || it.status == ai.maynards.mesh.brain.RunState.Status.FAILED }
        }
        if (result.status == ai.maynards.mesh.brain.RunState.Status.READY) return currentModel!!.name
    }

    val candidates = withContext(Dispatchers.IO) { shelf.scan() }
        .filter { !needsVision || shelf.projector(it) != null }
        .sortedByDescending { it.fileBytes }
    val selected = candidates.firstNotNullOfOrNull { model ->
        val plan = ai.maynards.mesh.brain.Planner.plan(model, devices,
            hostExtra = shelf.projector(model)?.length() ?: 0, preferSplit = false)
        plan.takeIf { it.verdict != ai.maynards.mesh.brain.Verdict.NOT_POSSIBLE }
    } ?: throw IllegalStateException(if (needsVision)
        "No installed vision model with its matching mmproj fits this mesh. Add one in Models."
    else "No installed text model fits this mesh. Add one in Models.")

    runner.run(selected)
    val result = kotlinx.coroutines.withTimeout(10 * 60_000L) {
        runner.state.first { it.status == ai.maynards.mesh.brain.RunState.Status.READY || it.status == ai.maynards.mesh.brain.RunState.Status.FAILED }
    }
    if (result.status != ai.maynards.mesh.brain.RunState.Status.READY)
        throw IllegalStateException(result.step.ifBlank { "Could not start ${selected.model.name}" })
    return selected.model.name
}

/**
 * The one thing to do next, whatever the state is. Without this a person opening the app has to
 * guess the order: add a device, pick a model, then ask it something. Now it says so, and the
 * button goes there.
 */
@Composable
private fun NextStep(
    run: ai.maynards.mesh.brain.RunState,
    peers: Int,
    onGo: (Int) -> Unit,
    onShowQr: () -> Unit,
) {
    val st = run.status
    val (title, why, label, action, tone) = when {
        st == ai.maynards.mesh.brain.RunState.Status.READY ->
            Q5("Ready", "${run.plan?.model?.name ?: "A model"} is running. Ask it anything.", "Open the chat", { onGo(2) }, Term)
        st == ai.maynards.mesh.brain.RunState.Status.LOADING || st == ai.maynards.mesh.brain.RunState.Status.STARTING ->
            Q5("Starting", "${run.step}. A device that has held these layers before keeps them.", "Watch it", { onGo(2) }, Warn)
        st == ai.maynards.mesh.brain.RunState.Status.FAILED ->
            Q5("It stopped", run.step, "Pick another model", { onGo(1) }, Bad)
        peers == 0 ->
            Q5("Add a device", "This phone is alone. Another phone or a laptop brings its memory into the pool.",
                "Show the code", onShowQr, Term)
        else ->
            Q5("Pick a model", "$peers device${if (peers == 1) "" else "s"} joined. Choose what to run across them.",
                "Choose a model", { onGo(1) }, Term)
    }
    NBox(fill = if (tone == Term) HostGreen else if (tone == Bad) Danger else Yellow) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(tone, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(9.dp))
                Label("Next")
            }
            Title(title, 22)
            Text(why, fontSize = 14.sp, color = Muted, lineHeight = 19.sp)
            NButton(label, fill = Term, onClick = action)
        }
    }
}

/** Five things, because Kotlin's Triple stops at three. */
private data class Q5(
    val title: String, val why: String, val label: String,
    val action: () -> Unit, val tone: androidx.compose.ui.graphics.Color,
)

@Composable
private fun DeviceRow(
    name: String, sub: String, usable: Long, heat: Float, battery: Int, charging: Boolean, rtt: Double,
    fill: Color, laptop: Boolean, holding: ai.maynards.mesh.brain.Slice? = null,
) {
    NBox(fill = fill, shadow = 4.dp, pad = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BigIcon(if (laptop) Icons.Outlined.Laptop else Icons.Outlined.PhoneAndroid, Paper, 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Black, fontSize = 15.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Mono(sub, 10, Muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${gb(holding?.bytes ?: usable)} GB", fontWeight = FontWeight.Black, fontSize = 20.sp, color = Ink)
                    Mono(if (holding != null) "layers ${holding.from}-${holding.to - 1} · ${gb(usable)} free" else "for models", 10, Muted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(Icons.Outlined.Thermostat, when { heat < 0 -> "—"; heat < 0.5f -> "cool"; heat < 0.8f -> "warm"; else -> "hot" })
                Chip(Icons.Outlined.BatteryChargingFull, "$battery%${if (charging) "+" else ""}")
                if (rtt >= 0) Chip(Icons.Outlined.Speed, "%.0f ms".format(rtt), if (rtt > 60) Danger else Paper)
            }
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, text: String, fill: Color = Paper) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.background(fill, shape).border(2.dp, Ink, shape).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = Ink)
        Spacer(Modifier.width(4.dp))
        Mono(text, 11)
    }
}

@Composable
private fun Soon(tab: Tab) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BigIcon(tab.icon, Yellow, 64.dp)
        Spacer(Modifier.height(12.dp))
        Title(tab.label, 26)
        Small("Coming next")
    }
}

@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Paper).border(Border, Ink).padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TABS.forEachIndexed { i, t ->
            val on = i == selected
            val shape = RoundedCornerShape(10.dp)
            Box(Modifier.weight(1f).padding(end = 3.dp, bottom = 3.dp)) {
                if (!on) Box(Modifier.matchParentSize().offset(3.dp, 3.dp).background(Shadow, shape))
                Column(
                    Modifier.offset(if (on) 3.dp else 0.dp, if (on) 3.dp else 0.dp).fillMaxWidth()
                        .background(if (on) Term else Paper, shape).border(Border, if (on) Term else Ink, shape)
                        .clickable { onSelect(i) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(t.icon, null, Modifier.size(20.dp), tint = if (on) Cream else Ink)
                    Text(t.label.uppercase(), fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 1.sp,
                        color = if (on) Cream else Ink)
                }
            }
        }
    }
}

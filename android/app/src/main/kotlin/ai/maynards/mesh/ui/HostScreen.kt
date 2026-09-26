package ai.maynards.mesh.ui

import ai.maynards.mesh.MeshService
import ai.maynards.mesh.engine.gb
import ai.maynards.mesh.mesh.MeshHost
import ai.maynards.mesh.mesh.Peer
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Mesh", Icons.Outlined.Hub),
    Tab("Models", Icons.Outlined.Inventory2),
    Tab("Use", Icons.Outlined.Chat),
    Tab("Stats", Icons.Outlined.BarChart),
)

@Composable
fun HostScreen(host: MeshHost, shelf: ai.maynards.mesh.brain.Shelf, runner: ai.maynards.mesh.brain.Runner, onChangeRole: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        runCatching { host.start() }
        MeshService.start(ctx, "Host running")
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val peers by host.peers.collectAsState()
    val me = rememberSpecs()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> MeshTab(host, onChangeRole)
                1 -> ModelsTab(shelf, meshDevices(me, peers.values)) { runner.run(it); tab = 2 }
                2 -> {
                    val run by runner.state.collectAsState()
                    UseTab(runner, canSee = run.plan?.model?.let { shelf.projector(it) } != null, onPickModel = { tab = 1 })
                }
                else -> Soon(TABS[tab])
            }
        }
        TabBar(tab) { tab = it }
    }
}

@Composable
private fun MeshTab(host: MeshHost, onChangeRole: () -> Unit) {
    val peers by host.peers.collectAsState()
    val token by host.token.collectAsState()
    val me = rememberSpecs()
    var showQr by remember { mutableStateOf(true) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Header()
        val pool = me.usableBytes + peers.values.sumOf { it.specs.usableBytes }
        NBox(fill = HostGreen) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Title("The brain", 30)
                    Spacer(Modifier.width(10.dp))
                    Sticker(if (peers.isEmpty()) "1 device" else "${peers.size + 1} devices", Paper)
                }
                Mono("${gb(pool)} GB pooled for models", 13)
            }
        }
        NBox(pad = 8.dp) {
            MeshWeb(
                listOf(WebNode("me", HostGreen, isHost = true)) +
                    peers.values.map { WebNode(it.specs.name, HelperPurple) },
                Modifier.fillMaxWidth().aspectRatio(1.4f),
            )
        }

        if (showQr) {
            NBox(fill = Yellow) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconLabel(Icons.Outlined.QrCode2, "Scan with a Helper")
                    Box(Modifier.background(Paper, RoundedCornerShape(8.dp)).border(Border, Ink, RoundedCornerShape(8.dp)).padding(10.dp)) {
                        QrImage(remember(token, peers.size) { host.invite().toJson() }, Modifier.size(220.dp))
                    }
                    Mono(host.invite().hosts.joinToString("  "), 11)
                }
            }
        }
        NButton(if (showQr) "Hide QR" else "Add device", fill = if (showQr) Paper else Yellow) { showQr = !showQr }

        Label("Devices")
        DeviceRow(me.name, "this phone · host", me.usableBytes, me.heat, me.battery, me.charging, -1.0, HostGreen, false)
        peers.values.sortedBy { it.specs.name }.forEach { p -> PeerRow(p) }
        if (peers.isEmpty()) Small("No helpers yet. Scan the QR from another phone.")

        NButton("Change role", fill = Paper, onClick = onChangeRole)
    }
}

@Composable
private fun PeerRow(p: Peer) = DeviceRow(
    p.specs.name, "${p.addr} · helper", p.specs.usableBytes, p.specs.heat, p.specs.battery, p.specs.charging,
    p.rttMs, HelperPurple, p.specs.kind == "laptop",
)

@Composable
private fun DeviceRow(
    name: String, sub: String, usable: Long, heat: Float, battery: Int, charging: Boolean, rtt: Double,
    fill: Color, laptop: Boolean,
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
                    Text("${gb(usable)} GB", fontWeight = FontWeight.Black, fontSize = 20.sp, color = Ink)
                    Mono("for models", 10, Muted)
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
                if (!on) Box(Modifier.matchParentSize().offset(3.dp, 3.dp).background(Ink, shape))
                Column(
                    Modifier.offset(if (on) 3.dp else 0.dp, if (on) 3.dp else 0.dp).fillMaxWidth()
                        .background(if (on) Ink else Paper, shape).border(Border, Ink, shape)
                        .clickable { onSelect(i) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(t.icon, null, Modifier.size(20.dp), tint = if (on) Yellow else Ink)
                    Text(t.label.uppercase(), fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 1.sp,
                        color = if (on) Paper else Ink)
                }
            }
        }
    }
}

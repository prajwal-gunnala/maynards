package ai.maynards.mesh.ui

import ai.maynards.mesh.brain.Catalog
import ai.maynards.mesh.brain.Device
import ai.maynards.mesh.brain.ModelInfo
import ai.maynards.mesh.brain.Plan
import ai.maynards.mesh.brain.Planner
import ai.maynards.mesh.brain.Shelf
import ai.maynards.mesh.brain.Verdict
import ai.maynards.mesh.engine.Specs
import ai.maynards.mesh.engine.gb
import ai.maynards.mesh.mesh.Peer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The devices as the planner sees them: this phone as Host, plus every joined Helper. */
fun meshDevices(me: Specs, peers: Collection<Peer>, hostCap: Long = 0): List<Device> =
    listOf(Device(me.id, "This phone", if (hostCap > 0) minOf(me.usableBytes, hostCap) else me.usableBytes, isHost = true, heat = me.heat, battery = me.battery, charging = me.charging, speedScore = ai.maynards.mesh.brain.Planner.chipSpeedScore(me.chip, me.name, true))) +
        peers.map { Device(it.id, it.specs.name, it.specs.usableBytes, rttMs = it.rttMs, heat = it.specs.heat, battery = it.specs.battery, charging = it.specs.charging, speedScore = ai.maynards.mesh.brain.Planner.chipSpeedScore(it.specs.chip, it.specs.name, false)) }

@Composable
fun ModelsTab(shelf: Shelf, devices: List<Device>, onRun: (Plan) -> Unit,
              peers: Collection<Peer> = emptyList(), downloads: ai.maynards.mesh.brain.Downloads? = null) {
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) { models = withContext(Dispatchers.IO) { shelf.scan() }; delay(5000) }
    }
    val onPhone = models.map { it.file }.toSet()
    val active by (downloads?.active ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap())).collectAsState()
    // who can hand us a file we don't have: file -> the laptop offering it
    // a catalog model is only offered if the laptop's copy is complete (a half-downloaded file would not load)
    val offers = peers.flatMap { p -> p.models.keys.map { it to p } }.toMap().filter { (f, p) ->
        val want = Catalog.all.firstOrNull { it.info.file == f }?.info?.fileBytes
        want == null || p.models[f] == want
    }
    val get: (String) -> (() -> Unit)? = { file ->
        offers[file]?.takeIf { downloads != null }?.let { p -> { downloads!!.get(p.modelUrl(file), file, p.models.getValue(file)) } }
    }
    val plans = models.map { Planner.plan(it, devices, hostExtra = shelf.projector(it)?.length() ?: 0) } +
        Catalog.all.filter { it.info.file !in onPhone }.map { Planner.plan(it.info, devices) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageHeader(
            "Models",
            "Every model this phone can see, sorted by whether the devices you have can actually run it. " +
                "${devices.size} device${if (devices.size == 1) "" else "s"} offering ${gb(devices.sumOf { it.usableBytes })} GB between them.",
        )
        if (plans.isEmpty()) NBox {
            EmptyState(
                "Nothing to run yet",
                "Put a .gguf file in this phone's model folder, or join a laptop that has one and pull it across the cable.",
            )
        }

        Section(Verdict.DOABLE, plans, onPhone, onRun, get, active)
        Section(Verdict.TIGHT, plans, onPhone, onRun, get, active)
        Section(Verdict.NOT_POSSIBLE, plans, onPhone, onRun, get, active)
        // files a laptop offers that we know nothing about yet: get them, then they are planned like the rest
        val notChat = listOf("mmproj", "tts", "asr", "whisper", "diffusion", "embed")
        val unknown = offers.keys.filter { f ->
            f !in onPhone && Catalog.all.none { it.info.file == f } && notChat.none { f.contains(it, ignoreCase = true) }
        }
        if (unknown.isNotEmpty()) {
            IconLabel(Icons.Outlined.Laptop, "On a laptop · ${unknown.size}")
            unknown.sorted().forEach { f ->
                NBox(fill = Paper, shadow = 4.dp, pad = 12.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(f.removeSuffix(".gguf"), fontWeight = FontWeight.Black, fontSize = 15.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Mono("${gb(offers.getValue(f).models.getValue(f))} GB · ${offers.getValue(f).specs.name}", 11, Muted)
                        GetRow(f, get(f), active[f])
                    }
                }
            }
        }
    }
}

private fun look(v: Verdict) = when (v) {
    Verdict.DOABLE -> Triple("Doable", HostGreen, Icons.Outlined.CheckCircle)
    Verdict.TIGHT -> Triple("Tight", Tight, Icons.Outlined.WarningAmber)
    Verdict.NOT_POSSIBLE -> Triple("Not possible", Danger, Icons.Outlined.Block)
}

@Composable
private fun Section(v: Verdict, plans: List<Plan>, onPhone: Set<String>, onRun: (Plan) -> Unit,
                    get: (String) -> (() -> Unit)?, active: Map<String, ai.maynards.mesh.brain.Download>) {
    val mine = plans.filter { it.verdict == v }
    if (mine.isEmpty()) return
    val (label, color, icon) = look(v)
    IconLabel(icon, "$label · ${mine.size}")
    mine.forEach { ModelCard(it, color, it.model.file in onPhone, onRun, get(it.model.file), active[it.model.file]) }
}

@Composable
private fun ModelCard(p: Plan, color: Color, onPhone: Boolean, onRun: (Plan) -> Unit,
                      get: (() -> Unit)?, progress: ai.maynards.mesh.brain.Download?) {
    val m = p.model
    NBox(fill = color, shadow = 5.dp, pad = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, fontWeight = FontWeight.Black, fontSize = 17.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Mono("${gb(m.fileBytes)} GB · ${m.layers} layers", 11, Ink)
                }
                Sticker(look(p.verdict).first, Paper)
            }
            Text(p.reason, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Ink)
            if (p.slices.isNotEmpty()) LayerBar(p)
            when {
                !onPhone -> GetRow(p.model.file, get, progress)
                p.verdict != Verdict.NOT_POSSIBLE -> NButton("Run", fill = Paper) { onRun(p) }
            }
        }
    }
}

/** Not on the phone: get it from a laptop that has it, with a progress bar while it copies. */
@Composable
private fun GetRow(file: String, get: (() -> Unit)?, d: ai.maynards.mesh.brain.Download?) {
    when {
        d != null && d.error.isBlank() -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val shape = RoundedCornerShape(6.dp)
            Box(Modifier.fillMaxWidth().height(18.dp).background(Paper, shape).border(Border, Ink, shape)) {
                Box(Modifier.fillMaxWidth(d.fraction.coerceIn(0.02f, 1f)).fillMaxHeight().background(Yellow, shape).border(Border, Ink, shape))
            }
            Mono("${gb(d.done)} / ${gb(d.total)} GB · ${"%.0f".format(d.mbPerSec)} MB/s", 11, Ink)
        }
        get != null -> NButton(if (d != null) "Retry from laptop" else "Get from laptop", fill = Yellow, onClick = get)
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Sticker("not on phone", Cream, 0f)
            Spacer(Modifier.width(8.dp))
            Mono("join a laptop that has it", 11, Ink)
        }
    }
}

/** One bar, one coloured block per device, sized by how many layers it holds. */
@Composable
fun LayerBar(p: Plan) {
    val shape = RoundedCornerShape(6.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().height(28.dp).background(Paper, shape).border(Border, Ink, shape).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            p.slices.forEachIndexed { i, s ->
                Box(
                    Modifier.weight(s.count.coerceAtLeast(1).toFloat()).fillMaxHeight()
                        .background(if (i == 0) HostGreen else HelperPurple, RoundedCornerShape(3.dp))
                        .border(2.dp, Ink, RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("${s.count}", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Ink) }
            }
        }
        p.slices.forEach { s -> Mono("${s.name}: layers ${s.from}–${s.to - 1} · ${gb(s.bytes)} GB", 10, Ink) }
    }
}

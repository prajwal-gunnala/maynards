package ai.maynards.mesh.ui

import ai.maynards.mesh.engine.Specs
import ai.maynards.mesh.engine.gb
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** This phone's specs, refreshed every 2 seconds. */
@Composable
fun rememberSpecs(): Specs {
    val ctx = LocalContext.current
    var specs by remember { mutableStateOf(Specs.read(ctx)) }
    LaunchedEffect(Unit) {
        while (true) { delay(2000); specs = Specs.read(ctx) }
    }
    return specs
}

@Composable
fun SpecsCard(s: Specs, fill: Color = Paper) {
    NBox(fill = fill, shadow = 5.dp, pad = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BigIcon(if (s.kind == "laptop") Icons.Outlined.Laptop else Icons.Outlined.PhoneAndroid, HelperPurple)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontWeight = FontWeight.Black, fontSize = 17.sp, color = Ink)
                    Mono("${s.chip} · ${s.cores} cores · ${"%.1f".format(s.maxGhz)} GHz", 11, Muted)
                }
            }
            Meter(Icons.Outlined.Memory, "Memory", "${gb(s.freeBytes)} / ${gb(s.totalBytes)} GB free", s.freeBytes.toFloat() / s.totalBytes, HostGreen)
            Meter(Icons.Outlined.Bolt, "For the model", "${gb(s.usableBytes)} GB", s.usableBytes.toFloat() / s.totalBytes, Yellow)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat(Icons.Outlined.Thermostat, "Heat", heatLabel(s.heat), Modifier.weight(1f), heatColor(s.heat))
                Stat(if (s.charging) Icons.Outlined.BatteryChargingFull else Icons.Outlined.BatteryFull,
                    "Battery", "${s.battery}%", Modifier.weight(1f),
                    if (!s.charging && s.battery < 20) Danger else if (s.charging) HostGreen else Paper)
            }
        }
    }
}

private fun heatColor(h: Float) = when {
    h < 0 -> Paper
    h < 0.5f -> HostGreen
    h < 0.8f -> Tight
    else -> Danger
}

private fun heatLabel(h: Float) = when {
    h < 0 -> "—"
    h < 0.5f -> "cool"
    h < 0.8f -> "warm"
    else -> "hot"
}

@Composable
fun Meter(icon: ImageVector, label: String, value: String, frac: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { IconLabel(icon, label) }
            Mono(value, 12)
        }
        val shape = RoundedCornerShape(6.dp)
        Box(Modifier.fillMaxWidth().height(18.dp).background(Paper, shape).border(Border, Ink, shape)) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0.02f, 1f)).fillMaxHeight().background(color, shape).border(Border, Ink, shape))
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, label: String, value: String, modifier: Modifier, fill: Color) {
    val shape = RoundedCornerShape(8.dp)
    Column(modifier.background(fill, shape).border(Border, Ink, shape).padding(horizontal = 12.dp, vertical = 8.dp)) {
        IconLabel(icon, label)
        Text(value, fontWeight = FontWeight.Black, fontSize = 18.sp, color = Ink)
    }
}

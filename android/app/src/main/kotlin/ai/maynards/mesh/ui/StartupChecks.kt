package ai.maynards.mesh.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.maynards.mesh.engine.Offer
import ai.maynards.mesh.engine.Specs
import ai.maynards.mesh.engine.gb
import kotlinx.coroutines.delay

/** Preflight shown on every launch, before a Host or Helper role can start. */
@Composable
fun StartupChecks(onContinue: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var specs by remember(refresh) { mutableStateOf(Specs.read(ctx)) }
    var permissionRequestPending by remember { mutableStateOf(false) }
    var budgetConfirmed by remember { mutableStateOf(Offer.bytes(ctx) > 0L) }
    val existingOffer = Offer.bytes(ctx)
    val maxOfferGb = remember { Specs.offerCapacityBytes(ctx) / 1_000_000_000L }
        .coerceAtLeast(1L).coerceAtMost(16L)
    val suggested = minOf(4L, maxOfferGb).toFloat()
    var selectedGb by remember {
        mutableFloatStateOf(if (existingOffer > 0) (existingOffer / 1_000_000_000f).coerceIn(1f, maxOfferGb.toFloat()) else suggested)
    }
    LaunchedEffect(existingOffer, maxOfferGb) {
        if (existingOffer > 0) Offer.set(ctx, selectedGb.toLong())
    }

    val needsNotifications = Build.VERSION.SDK_INT >= 33
    val notificationsGranted = !needsNotifications || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val cameraGranted = ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val microphoneGranted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryUnrestricted = power.isIgnoringBatteryOptimizations(ctx.packageName)

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRequestPending = false
        refresh++
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh++
    }

    LaunchedEffect(Unit) {
        while (true) {
            specs = Specs.read(ctx)
            delay(2000)
        }
    }

    val allGranted = notificationsGranted && cameraGranted && microphoneGranted
    val ready = allGranted && batteryUnrestricted && budgetConfirmed
    val availableBudgetChoices = Offer.choices.filter { it > 0L && it <= maxOfferGb }.ifEmpty { listOf(1L) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header()
        PageHeader(
            "Prepare this phone",
            "Check background readiness and choose its memory limit before MeshAI starts as Host or Helper.",
        )

        NBox(fill = HelperPurple) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Label("DEVICE CHECK")
                Text(specs.name, fontSize = 19.sp, fontWeight = FontWeight.Black, color = Ink)
                Mono("${gb(specs.totalBytes)} GB RAM · ${specs.battery}% battery${if (specs.charging) " · charging" else ""}", 12, Muted)
                Mono("Currently available to model: ${gb(specs.usableBytes)} GB", 12, Term)
            }
        }

        NBox {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("MEMORY BUDGET")
                Text("How much may this phone lend?", fontWeight = FontWeight.Black, fontSize = 17.sp, color = Ink)
                Text(
                    "This is a ceiling for model weights and inference state. Android's reserve stays outside it, and real free memory can make the usable amount lower. MeshAI will keep the model on one phone when it fits this limit; it will split only when needed and the connected devices can hold it.",
                    fontSize = 13.sp, lineHeight = 19.sp, color = Muted,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${"%.0f".format(selectedGb)} GB", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Term)
                    Spacer(Modifier.weight(1f))
                    Mono("${gb(specs.totalBytes - specs.reserveBytes)} GB max · reserve ${gb(specs.reserveBytes)} GB", 10, Muted)
                }
                Slider(
                    value = selectedGb.coerceIn(1f, maxOfferGb.toFloat()),
                    onValueChange = { value ->
                        val wholeGb = value.toLong().coerceIn(1L, maxOfferGb)
                        selectedGb = wholeGb.toFloat()
                        Offer.set(ctx, wholeGb)
                        budgetConfirmed = true
                    },
                    valueRange = 1f..maxOfferGb.toFloat(),
                    steps = (maxOfferGb - 1L).toInt().coerceAtLeast(0),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    availableBudgetChoices.forEach { amount ->
                        val chosen = budgetConfirmed && selectedGb.toLong() == amount
                        Text(
                            "$amount GB",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (chosen) Cream else Muted,
                            modifier = Modifier
                                .clipCard(if (chosen) Term else Raised, if (chosen) Term else Line)
                                .clickable {
                                    selectedGb = amount.toFloat()
                                    Offer.set(ctx, amount)
                                    budgetConfirmed = true
                                }
                                .padding(vertical = 9.dp),
                        )
                    }
                }
                if (!budgetConfirmed) Mono("Move the slider or choose an amount to confirm this phone's limit.", 11, Warn)
            }
        }

        NBox {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("REQUIRED FOR BACKGROUND MESH")
                CheckLine("Notifications", notificationsGranted, if (needsNotifications) "MeshAI must show its running-service notice." else "Handled by this Android version.")
                CheckLine("Camera", cameraGranted, "Used to scan a Helper invite and capture design photos.")
                CheckLine("Microphone", microphoneGranted, "Used only when you choose voice input.")
                CheckLine("Battery restrictions", batteryUnrestricted, "Allow MeshAI to keep inference alive with the screen off.")

                if (!allGranted) {
                    NButton("Allow app permissions", fill = Term, enabled = !permissionRequestPending) {
                        val requested = buildList {
                            add(Manifest.permission.CAMERA)
                            add(Manifest.permission.RECORD_AUDIO)
                            if (needsNotifications) add(Manifest.permission.POST_NOTIFICATIONS)
                        }.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                        if (requested.isEmpty()) refresh++ else {
                            permissionRequestPending = true
                            permissionLauncher.launch(requested.toTypedArray())
                        }
                    }
                    if (permissionRequestPending) Mono("Finish the Android permission prompts, then MeshAI will recheck them.", 11, Muted)
                }

                if (!batteryUnrestricted) {
                    NButton("Allow background battery use") {
                        openBatterySettings(ctx, settingsLauncher)
                    }
                }
            }
        }

        NBox(fill = Yellow) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Label("PHONE-MAKER BACKGROUND SETTINGS")
                Text("Some iQOO / vivo power controls cannot be checked by Android apps. After setup, allow MeshAI auto-start and background activity in the phone's battery settings for the most reliable screen-off operation.", fontSize = 12.sp, lineHeight = 18.sp, color = Ink)
                Text("Open app settings", color = Term, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        settingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    }.padding(vertical = 5.dp))
            }
        }

        NButton(
            "Continue to Host / Helper",
            fill = Term,
            enabled = ready,
            onClick = onContinue,
        )
        if (!ready) Mono("Finish the required checks and confirm a memory limit to continue.", 11, Muted)
    }
}

@Composable
private fun CheckLine(title: String, ok: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (ok) "✓" else "○", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (ok) Term else Warn)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(detail, fontSize = 11.sp, lineHeight = 15.sp, color = Muted)
        }
        Sticker(if (ok) "ready" else "needed", if (ok) HostGreen else Yellow)
    }
}

@Composable
private fun Modifier.clipCard(fill: Color, stroke: Color): Modifier = this
    .background(fill, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
    .border(Border, stroke, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
    .padding(horizontal = 6.dp)

@SuppressLint("BatteryLife")
private fun openBatterySettings(
    context: Context,
    launcher: androidx.activity.compose.ManagedActivityResultLauncher<Intent, androidx.activity.result.ActivityResult>,
) {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    try {
        launcher.launch(request)
    } catch (_: Exception) {
        try { launcher.launch(fallback) } catch (_: Exception) {
            launcher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }
}

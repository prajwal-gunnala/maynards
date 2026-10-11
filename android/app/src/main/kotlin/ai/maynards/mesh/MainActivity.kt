package ai.maynards.mesh

import ai.maynards.mesh.ui.Header
import ai.maynards.mesh.ui.HelperScreen
import ai.maynards.mesh.ui.HostScreen
import ai.maynards.mesh.ui.HelperPurple
import ai.maynards.mesh.ui.HostGreen
import ai.maynards.mesh.ui.Label
import ai.maynards.mesh.ui.MeshTheme
import ai.maynards.mesh.ui.MeshWeb
import ai.maynards.mesh.ui.NBox
import ai.maynards.mesh.ui.NButton
import ai.maynards.mesh.ui.Paper
import ai.maynards.mesh.ui.RolePicker
import ai.maynards.mesh.ui.StartupChecks
import ai.maynards.mesh.ui.Title
import ai.maynards.mesh.ui.WebNode
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as MeshApp
        ai.maynards.mesh.ui.Themes.load(this)
        setContent {
            // status and navigation bar icons dark on the light theme, light on the dark one
            androidx.compose.runtime.SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = ai.maynards.mesh.ui.Themes.light
                    isAppearanceLightNavigationBars = ai.maynards.mesh.ui.Themes.light
                }
            }
            MeshTheme {
                Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    // The readiness gate must lead to a deliberate role choice. Restoring HOST
                    // here made two phones that had both previously been Hosts silently start
                    // as Hosts again, with no visible way to assign one as a Helper.
                    var role by remember { mutableStateOf<Role?>(null) }
                    var lastRole by remember { mutableStateOf(RoleStore.load(this)) }
                    var startupComplete by remember { mutableStateOf(false) }
                    val pick: (Role?) -> Unit = { chosen ->
                        val previous = RoleStore.load(this)
                        if (chosen != null && previous != null && chosen != previous) {
                            // A role switch must not leave the old listener/helper session alive.
                            app.runner.stop()
                            app.host.stop()
                            app.client.leave()
                            MeshService.stop(this)
                        }
                        RoleStore.save(this, chosen)
                        lastRole = chosen
                        role = chosen
                    }
                    if (!startupComplete) {
                        StartupChecks {
                            // Do not start/join a mesh from an automation/deep-link intent until the
                            // owner has finished the same device-readiness checks as the normal UI.
                            handleAdbExtras(intent)
                            val requestedRole = intent?.getStringExtra("role")
                                ?.let { runCatching { Role.valueOf(it) }.getOrNull() }
                            if (requestedRole != null) {
                                // Keep the explicit ADB/deep-link workflow used by controlled tests.
                                role = RoleStore.load(this)
                            } else {
                                // Normal launches always let the owner choose Host or Helper.
                                // The previous selection is shown as context, never auto-started.
                                lastRole = RoleStore.load(this)
                                role = null
                            }
                            startupComplete = true
                        }
                    } else when (val r = role) {
                        null -> RolePicker(currentRole = lastRole, onPick = pick)
                        Role.HELPER -> HelperScreen(app.engine, app.client, onChangeRole = { pick(null) })
                        Role.HOST -> HostScreen(app.host, app.shelf, app.runner, app.stats, app.downloads, app.hostCapBytes, app.autoRun.also { app.autoRun = null }, app.autoRunHelpers, onChangeRole = { app.runner.stop(); app.host.stop(); pick(null) })
                    }
                }
            }
        }
    }
}

/**
 * Lets a laptop drive the phone without touching the screen:
 *   adb shell am start -n ai.maynards.mesh/.MainActivity --es role HELPER --ez start true [--es bind <ip>]
 *   adb shell am start -n ai.maynards.mesh/.MainActivity --es role HELPER --es join '<invite json>'
 *   adb shell am start -n ai.maynards.mesh/.MainActivity --es role HOST --es cap_gb 3   (test: Host pretends to have 3 GB)
 *   adb shell am start -n ai.maynards.mesh/.MainActivity --es role HOST --es run Qwen3-8B   (plan and run that model)
 *   ... --es run Qwen3-Coder --ei helpers 2   (wait for 2 Helpers first)
 */
private fun ComponentActivity.handleAdbExtras(i: android.content.Intent?) {
    val role = i?.getStringExtra("role")?.let { runCatching { Role.valueOf(it) }.getOrNull() } ?: return
    RoleStore.save(this, role)
    val app = application as MeshApp
    i.getStringExtra("cap_gb")?.toDoubleOrNull()?.let { app.hostCapBytes = (it * 1e9).toLong() }
    app.autoRun = i.getStringExtra("run")
    app.autoRunHelpers = i.getIntExtra("helpers", 0)
    if (role == Role.HELPER && i.getBooleanExtra("start", false)) {
        // --es bind <ip> picks the link (e.g. the USB tether); otherwise the best private link
        val ip = i.getStringExtra("bind") ?: ai.maynards.mesh.engine.Net.best()?.ip ?: return
        MeshService.start(this, "Helper ready")
        app.engine.startHelper(ip, ai.maynards.mesh.ui.helperThreads())
    }
    i.getStringExtra("join")?.let(ai.maynards.mesh.mesh.Invite::parse)?.let {
        MeshService.start(this, "Helper joined")
        app.client.join(it)
    }
}

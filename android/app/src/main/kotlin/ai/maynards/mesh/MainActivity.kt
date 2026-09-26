package ai.maynards.mesh

import ai.maynards.mesh.ui.Header
import ai.maynards.mesh.ui.HelperPurple
import ai.maynards.mesh.ui.HostGreen
import ai.maynards.mesh.ui.Label
import ai.maynards.mesh.ui.MeshTheme
import ai.maynards.mesh.ui.MeshWeb
import ai.maynards.mesh.ui.NBox
import ai.maynards.mesh.ui.NButton
import ai.maynards.mesh.ui.Paper
import ai.maynards.mesh.ui.RolePicker
import ai.maynards.mesh.ui.Title
import ai.maynards.mesh.ui.WebNode
import android.os.Bundle
import android.view.WindowManager
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
        // Staying on screen keeps us the top app, which is what earns the big CPU cores.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MeshTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var role by remember { mutableStateOf(RoleStore.load(this)) }
                    val pick: (Role?) -> Unit = { RoleStore.save(this, it); role = it }
                    when (val r = role) {
                        null -> RolePicker(onPick = pick)
                        else -> RoleHome(r, onChangeRole = { pick(null) })
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RoleHome(role: Role, onChangeRole: () -> Unit) {
    val host = role == Role.HOST
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(24.dp))
        Header()
        NBox(fill = if (host) HostGreen else HelperPurple) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label(if (host) "This phone is" else "This phone is a")
                Title(if (host) "The brain" else "Helper", 34)
            }
        }
        NBox(pad = 8.dp) {
            MeshWeb(
                listOf(WebNode("me", if (host) HostGreen else HelperPurple, isHost = true)),
                Modifier.fillMaxWidth().aspectRatio(1.4f),
            )
        }
        NButton("Change role", fill = Paper, onClick = onChangeRole)
    }
}

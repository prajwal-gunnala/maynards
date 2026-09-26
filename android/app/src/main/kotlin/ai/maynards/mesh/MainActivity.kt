package ai.maynards.mesh

import ai.maynards.mesh.ui.MeshTheme
import ai.maynards.mesh.ui.Muted
import ai.maynards.mesh.ui.RolePicker
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(if (role == Role.HOST) "Host" else "Helper", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("Ready", color = Muted)
        TextButton(onClick = onChangeRole) { Text("Change role") }
    }
}

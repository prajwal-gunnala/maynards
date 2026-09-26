package ai.maynards.mesh.ui

import ai.maynards.mesh.Role
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RolePicker(onPick: (Role) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        WebBackdrop(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(24.dp))
            Header()
            NBox(fill = Paper, pad = 8.dp) {
                MeshWeb(
                    listOf(
                        WebNode("host", HostGreen, isHost = true),
                        WebNode("phone", HelperPurple),
                        WebNode("laptop", HelperPurple),
                    ),
                    Modifier.fillMaxWidth().aspectRatio(1.4f),
                )
            }
            Label("Pick this phone's role")
            RoleCard("Host", "the brain", "Runs the model. You chat here.", HostGreen) { onPick(Role.HOST) }
            RoleCard("Helper", "lends memory", "Holds part of the model.", HelperPurple) { onPick(Role.HELPER) }
        }
    }
}

@Composable
fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            Box(Modifier.size(52.dp).offset(4.dp, 4.dp).background(Shadow, RoundedCornerShape(12.dp)))
            Box(
                Modifier.size(52.dp).background(Yellow, RoundedCornerShape(12.dp))
                    .border(Border, Ink, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("M", color = Ink, fontWeight = FontWeight.Black, fontSize = 26.sp) }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Title("MeshAI", 26)
            Small("Your devices. One brain.")
        }
    }
}

@Composable
private fun RoleCard(title: String, sticker: String, line: String, fill: Color, onClick: () -> Unit) {
    NBox(Modifier.fillMaxWidth().clickable(onClick = onClick), fill = fill) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Title(title, 26)
                Spacer(Modifier.width(10.dp))
                Sticker(sticker, Paper)
            }
            Small(line, Ink)
        }
    }
}

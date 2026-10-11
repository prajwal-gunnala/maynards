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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RolePicker(currentRole: Role? = null, onPick: (Role) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        WebBackdrop(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(18.dp))
            Header()
            PageHeader(
                "Which is this phone?",
                "Choose this phone's role for this launch. Use one Host to plan and answer, and choose Helper on each device that should lend memory.",
            )
            currentRole?.let {
                NBox(fill = Yellow, pad = 10.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Label("LAST USED · ${it.name}")
                        Small("MeshAI asks each launch so two phones do not silently start as Hosts again.", Ink)
                    }
                }
            }
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
            Section("Pick a role")
            RoleCard("Host", "plans and answers", "Runs the request, chooses a compatible model plan, and shows the result. Start with one Host per mesh.", HostGreen) { onPick(Role.HOST) }
            RoleCard("Helper", "lends memory + compute", "Choose this on the other phone, then scan the Host's pairing code. The Helper runs its assigned model layers.", HelperPurple) { onPick(Role.HELPER) }
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
        Column(Modifier.weight(1f)) {
            Title("MeshAI", 26)
            Small("Your devices. One brain.")
        }
        ThemeSwitch()
    }
}

/** Light or dark, remembered on this phone. */
@Composable
fun ThemeSwitch() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(
        Modifier.size(40.dp).background(Raised, RoundedCornerShape(20.dp)).border(Border, Line, RoundedCornerShape(20.dp))
            .clickable { Themes.set(ctx, !Themes.light) },
        contentAlignment = Alignment.Center,
    ) { Text(if (Themes.light) "☾" else "☀", color = Ink, fontSize = 18.sp) }
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

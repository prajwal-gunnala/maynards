package ai.maynards.mesh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.DesignServices
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun UseHome(onChoose: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader("Use the mesh", "Choose a task. MeshAI checks the installed model, memory, battery and connected devices before it runs.")
        UseCaseCard("chat", "Mesh Chat", "Chat with the model currently running on this mesh. The Host handles the request and assigned Helpers compute their model layers.", "The chat screen shows the active model and live route.", onChoose)
        UseCaseCard("origami", "Origami Workshop", "Design organizers and furniture from a prompt or photo, preview the parametric result, and export fabrication files.", "Model choice is automatic; geometry remains deterministic.", onChoose)
        UseCaseCard("case", "Legal Case Room", "Work with original case documents and get source-linked timelines, comparisons and answers.", "Local import, timeline, search and cited answers. No OCR yet.", onChoose)
        UseCaseCard("developer", "Developer Workspace", "Open a private project folder and ask about it. Answers cite file and lines.", "Read-only. No code is run or changed.", onChoose)
    }
}

@Composable
private fun UseCaseCard(id: String, title: String, description: String, note: String, onChoose: (String) -> Unit) {
    val icon = when (id) {
        "chat" -> Icons.Outlined.Chat
        "origami" -> Icons.Outlined.DesignServices
        "case" -> Icons.Outlined.Shield
        "developer" -> Icons.Outlined.Code
        else -> Icons.Outlined.TipsAndUpdates
    }
    NBox(fill = Paper, pad = 13.dp, shadow = 4.dp, modifier = Modifier.fillMaxWidth().clickable { onChoose(id) }) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(icon, null, tint = Term)
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Black, color = Ink)
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Text("OPEN →", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Term)
            }
            Text(description, fontSize = 13.sp, lineHeight = 18.sp, color = Ink)
            Text(note, fontSize = 11.sp, lineHeight = 15.sp, color = Muted)
        }
    }
}

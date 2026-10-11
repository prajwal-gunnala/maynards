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
        PageHeader("Use", "Pick a task.")
        UseCaseCard("chat", "Chat", "Talk to the running model.", onChoose)
        UseCaseCard("case", "Case Room", "Legal files · cited answers · timeline", onChoose)
        UseCaseCard("developer", "Code", "Private code · answers with file:lines", onChoose)
        UseCaseCard("origami", "Origami", "Furniture from a prompt or photo", onChoose)
    }
}

@Composable
private fun UseCaseCard(id: String, title: String, description: String, onChoose: (String) -> Unit) {
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
        }
    }
}

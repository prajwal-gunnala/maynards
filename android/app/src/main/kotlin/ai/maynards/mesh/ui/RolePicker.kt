package ai.maynards.mesh.ui

import ai.maynards.mesh.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RolePicker(onPick: (Role) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("MeshAI", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("Pick this phone's role", color = Muted)
        Spacer(Modifier.height(32.dp))
        RoleCard("Host", "Runs the model. You chat here.") { onPick(Role.HOST) }
        Spacer(Modifier.height(16.dp))
        RoleCard("Helper", "Lends its memory to the Host.") { onPick(Role.HELPER) }
    }
}

@Composable
private fun RoleCard(title: String, line: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Accent)
            Text(line, color = Muted)
        }
    }
}

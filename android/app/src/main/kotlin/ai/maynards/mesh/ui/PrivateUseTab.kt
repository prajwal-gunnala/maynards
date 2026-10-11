package ai.maynards.mesh.ui

import ai.maynards.mesh.brain.Answer
import ai.maynards.mesh.brain.Chat
import ai.maynards.mesh.brain.ChatProgress
import ai.maynards.mesh.brain.RunState
import ai.maynards.mesh.brain.Runner
import ai.maynards.mesh.brain.Turn
import ai.maynards.mesh.mesh.Peer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_CASE_DOCUMENTS = 8

/** Case Room: files read on this Host, a date timeline, and answers that cite the passages. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun PrivateUseTab(runner: Runner, peers: Collection<Peer>, onEnsureModel: suspend () -> String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var documents by remember { mutableStateOf<List<CaseDocument>>(emptyList()) }
    var expandedPage by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var evidence by remember { mutableStateOf<List<CaseEvidence>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(0) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val available = MAX_CASE_DOCUMENTS - documents.size
            if (available <= 0) {
                status = "Up to $MAX_CASE_DOCUMENTS files"
            } else {
                importing = true
                status = "Reading…"
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        val loaded = ArrayList<CaseDocument>()
                        val failures = ArrayList<String>()
                        uris.take(available).forEach { uri ->
                            runCatching { CaseDocuments.load(context, uri) }
                                .onSuccess { loaded += it }
                                .onFailure { failures += (it.message ?: "Could not read selected file") }
                        }
                        loaded to failures
                    }
                    documents = documents + outcome.first
                    evidence = emptyList()
                    status = buildString {
                        if (outcome.first.isNotEmpty()) append("Added ${outcome.first.size}")
                        if (uris.size > available) append(" · only $available fit")
                        if (outcome.second.isNotEmpty()) append(" · ${outcome.second.joinToString(" ")}")
                        if (isBlank()) append("Could not read these files")
                    }
                    importing = false
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        PageHeader("Case Room", "Files stay on this phone.")
        NButton(if (importing) "Reading…" else "Add files", fill = Term,
            enabled = !importing && documents.size < MAX_CASE_DOCUMENTS) {
            picker.launch(arrayOf("application/pdf", "text/plain", "text/markdown", "text/*"))
        }
        if (status.isNotBlank()) Mono(status, 11, if (status.startsWith("Could not")) Bad else Term)

        if (documents.isEmpty()) {
            NBox(fill = Paper) { EmptyState("No files", "PDF, TXT or Markdown") }
            return@Column
        }

        documents.forEachIndexed { index, document ->
            val docKey = "$index:${document.name}"
            NBox(fill = Paper, pad = 12.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Label(document.name)
                    Mono("${document.pages.size} sections · ${document.extractedChars} chars" +
                        if (document.scannedPages > 0) " · ${document.scannedPages} scanned (unread)" else "", 10)
                    document.pages.firstOrNull { it.text.isNotBlank() }?.let { firstPage ->
                        Text(if (expandedPage == docKey) "Hide" else "Preview",
                            Modifier.clickable { expandedPage = if (expandedPage == docKey) null else docKey }.padding(vertical = 6.dp),
                            fontSize = 12.sp, color = Term)
                        if (expandedPage == docKey) NBox(fill = Cream, pad = 10.dp) {
                            Text(firstPage.text, fontSize = 12.sp, lineHeight = 17.sp, color = Ink)
                        }
                    }
                }
            }
        }
        NButton("Clear", fill = Paper, enabled = !importing) {
            documents = emptyList(); expandedPage = null; evidence = emptyList(); query = ""; status = "Cleared"
        }

        ModeSwitch(listOf("Ask", "Timeline"), mode) { mode = it }

        if (mode == 1) {
            CaseTimelineView(remember(documents) { CaseTimeline.extract(documents) })
            return@Column
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(240) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Question") },
            placeholder = { Text("When is delivery due?") },
            maxLines = 3,
            enabled = !searching,
        )
        NButton("Search", fill = Term, enabled = query.isNotBlank() && !searching) {
            searching = true
            scope.launch {
                val (matches, whole) = withContext(Dispatchers.Default) {
                    val found = CaseDocuments.retrieve(documents, query.trim())
                    if (found.isNotEmpty()) found to false else CaseDocuments.wholeCase(documents) to true
                }
                evidence = matches
                searching = false
                status = when {
                    matches.isEmpty() -> "No match. Try other words."
                    whole -> "No match · using all ${matches.size} passages"
                    else -> "${matches.size} passages"
                }
            }
        }
        evidence.forEach { source ->
            NBox(fill = Paper, pad = 12.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("${source.id} · ${source.fileName} · ${source.location}")
                    Text(source.text, fontSize = 12.sp, lineHeight = 17.sp, color = Ink)
                }
            }
        }
        CitedAsk(runner, onEnsureModel, evidence, query, CaseCitations.system, busy = searching)
    }
}

@Composable
private fun CaseTimelineView(events: List<CaseEvent>) {
    NBox(fill = Paper, pad = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Mono(if (events.isEmpty()) "No dates found" else "${events.size} dates", 11)
            events.forEach { e ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(e.label, fontSize = 13.sp, color = Term,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(e.sentence, fontSize = 12.sp, lineHeight = 17.sp, color = Ink)
                    Mono("${e.fileName} · ${e.location}", 10)
                }
            }
        }
    }
}


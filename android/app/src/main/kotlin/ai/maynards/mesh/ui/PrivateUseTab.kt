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

/** Case-room stages: local import, local source search, then a cited answer from the running model. */
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
    var timeline by remember { mutableStateOf<List<CaseEvent>?>(null) }
    var wholeCase by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val available = MAX_CASE_DOCUMENTS - documents.size
            if (available <= 0) {
                status = "This first version supports up to $MAX_CASE_DOCUMENTS documents per open case."
            } else {
                importing = true
                status = "Reading selected files on this Host…"
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
                    timeline = null
                    evidence = emptyList()
                    status = buildString {
                        if (outcome.first.isNotEmpty()) append("Loaded ${outcome.first.size} document(s) locally.")
                        if (uris.size > available) append(" Only the first $available were considered; add the rest after removing a document.")
                        if (outcome.second.isNotEmpty()) append(" ${outcome.second.joinToString(" ")}")
                        if (isBlank()) append("No readable documents were selected.")
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
        PageHeader(
            "Legal Case Room",
            "Import original case documents, find the passages, and ask for an answer that cites them.",
        )
        NBox(fill = HostGreen, pad = 13.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Label("LOCAL FILES · LOCAL SEARCH · CITED ANSWERS")
                Text(
                    "Files and searches stay on the Host in screen memory. Ask sends the matched passages to the model on this Host; Helpers holding layers compute on its activations. Nothing goes to the laptop monitor or MeshAI storage.",
                    fontSize = 12.sp, lineHeight = 18.sp, color = Ink,
                )
                Text(
                    "Choose files stored on this phone. A cloud-backed file provider may fetch its file when Android opens it. Only PDF, TXT, and Markdown are supported. Scanned-page OCR is not included yet.",
                    fontSize = 11.sp, lineHeight = 16.sp, color = Muted,
                )
            }
        }

        NButton(
            if (importing) "Reading documents…" else "Add case documents",
            fill = Term,
            enabled = !importing && documents.size < MAX_CASE_DOCUMENTS,
        ) {
            picker.launch(arrayOf("application/pdf", "text/plain", "text/markdown", "text/*"))
        }
        if (documents.isNotEmpty()) {
            NButton("Clear open case", fill = Paper, enabled = !importing) {
                documents = emptyList()
                timeline = null
                expandedPage = null
                evidence = emptyList()
                query = ""
                status = "The open case was cleared from this screen. This is not a secure memory-erasure guarantee."
            }
        }
        if (status.isNotBlank()) Mono(status, 11, if (status.startsWith("Could not")) Bad else Term)

        if (documents.isEmpty()) {
            NBox(fill = Paper) {
                EmptyState("No case files open", "Add a text PDF, TXT, or Markdown file.")
            }
        }

        documents.forEachIndexed { index, document ->
            val docKey = "$index:${document.name}"
            NBox(fill = Paper, pad = 12.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Label(document.name)
                    Mono("${document.pages.size} text section(s) · ${document.extractedChars} extracted characters" +
                        if (document.scannedPages > 0) " · ${document.scannedPages} page(s) have no selectable text" else "", 10)
                    document.pages.firstOrNull { it.text.isNotBlank() }?.let { firstPage ->
                        Text(
                            if (expandedPage == docKey) "Hide extraction preview · ${firstPage.location}" else "Inspect extracted text · ${firstPage.location} →",
                            Modifier.fillMaxWidth().clickable { expandedPage = if (expandedPage == docKey) null else docKey }.padding(vertical = 8.dp),
                            fontSize = 12.sp, color = Term,
                        )
                        if (expandedPage == docKey) {
                            NBox(fill = Cream, pad = 10.dp) {
                                Text(firstPage.text, fontSize = 12.sp, lineHeight = 17.sp, color = Ink)
                            }
                        }
                    }
                }
            }
        }

        if (documents.isNotEmpty()) {
            Label("TIMELINE")
            NButton(if (timeline == null) "Build timeline" else "Hide timeline", fill = Paper) {
                timeline = if (timeline == null) CaseTimeline.extract(documents) else null
            }
            timeline?.let { CaseTimelineView(it) }
        }

        if (documents.isNotEmpty()) {
            Label("SEARCH ORIGINAL EXTRACTED TEXT")
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(240) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Question or terms to find") },
                placeholder = { Text("e.g. delivery date") },
                maxLines = 3,
                enabled = !searching,
            )
            NButton("Search this case locally", fill = Term, enabled = query.isNotBlank() && !searching) {
                searching = true
                status = "Searching extracted source text on this Host…"
                scope.launch {
                    val (matches, whole) = withContext(Dispatchers.Default) {
                        val found = CaseDocuments.retrieve(documents, query.trim())
                        if (found.isNotEmpty()) found to false else CaseDocuments.wholeCase(documents) to true
                    }
                    evidence = matches
                    wholeCase = whole && matches.isNotEmpty()
                    searching = false
                    status = when {
                        matches.isEmpty() -> "No passage matched, and the case is too large to send whole. Try other words."
                        whole -> "No keyword match · the whole case (${matches.size} passage(s)) is small enough to ask."
                        else -> "Found ${matches.size} matching passage(s). No model was called."
                    }
                }
            }
            if (wholeCase && evidence.isNotEmpty()) Mono("WHOLE CASE · no keyword match", 10, Warn)
            evidence.forEach { source ->
                NBox(fill = Paper, pad = 12.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Label("${source.id} · ${source.fileName} · ${source.location}")
                        Text(source.text, fontSize = 12.sp, lineHeight = 17.sp, color = Ink)
                    }
                }
            }
            CitedAsk(runner, onEnsureModel, evidence, query, CaseCitations.system, busy = searching)
            NBox(fill = Yellow, pad = 12.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("LOCAL SEARCH · SOURCE REFERENCES")
                    Text(
                        "Search ranks exact source passages by file and page/line. Ask sends only these passages to the model; every citation is checked against them. Answers are review aids, not legal advice.",
                        fontSize = 12.sp, lineHeight = 17.sp, color = Ink,
                    )
                }
            }
        }
    }
}

@Composable
private fun CaseTimelineView(events: List<CaseEvent>) {
    NBox(fill = Paper, pad = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Mono(if (events.isEmpty()) "No dates found" else "${events.size} dated sentence(s) · found by pattern, no model", 11)
            events.forEach { e ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(e.label + if (e.year == null) " · year not stated" else "", fontSize = 13.sp, color = Term,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(e.sentence, fontSize = 12.sp, lineHeight = 17.sp, color = Ink)
                    Mono("${e.fileName} · ${e.location}", 10)
                }
            }
        }
    }
}


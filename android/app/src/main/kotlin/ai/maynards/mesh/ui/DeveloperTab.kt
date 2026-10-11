package ai.maynards.mesh.ui

import ai.maynards.mesh.brain.Runner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ask about a private codebase: the folder is read on this Host and answers cite file:lines. */
@Composable
fun DeveloperTab(runner: Runner, onEnsureModel: suspend () -> String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf<CodeProject?>(null) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var evidence by remember { mutableStateOf<List<CaseEvidence>>(emptyList()) }
    var wholeProject by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        loading = true
        status = "Reading the folder on this Host…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { CodeFiles.load(context, tree) } }
                .onSuccess { p ->
                    project = p
                    evidence = emptyList()
                    status = "Loaded ${p.files.size} files · ${p.lines} lines" +
                        (if (p.skipped > 0) " · skipped ${p.skipped}" else "") +
                        (if (p.truncated) " · stopped at the size limit" else "")
                }
                .onFailure { status = "Could not read the folder: ${it.message}" }
            loading = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        PageHeader("Developer Workspace", "Ask about a private codebase. Answers cite file and lines.")
        NBox(fill = HostGreen, pad = 13.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("CODE STAYS ON THIS HOST")
                Text(
                    "The folder is read into memory here. Ask sends only the matched excerpts to the model on this Host; Helpers holding layers compute on its activations. Nothing is run, changed or uploaded.",
                    fontSize = 12.sp, lineHeight = 18.sp, color = Ink,
                )
                Mono("Skips .git, build, node_modules, binaries, .env and files over ${CodeFiles.MAX_FILE_BYTES / 1024} KB", 10)
            }
        }
        NButton(if (loading) "Reading folder…" else if (project == null) "Open project folder" else "Open another folder",
            fill = Term, enabled = !loading) { picker.launch(null) }
        project?.let { p ->
            NButton("Close project", fill = Paper, enabled = !loading) {
                project = null; evidence = emptyList(); query = ""
                status = "Closed. This is not a secure memory erasure."
            }
            NBox(fill = Paper, pad = 12.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Label(p.name)
                    Mono("${p.files.size} files · ${p.lines} lines", 11, Ink)
                    p.files.take(8).forEach { Mono(it.name, 10) }
                    if (p.files.size > 8) Mono("+ ${p.files.size - 8} more", 10)
                }
            }
        }
        if (status.isNotBlank()) Mono(status, 11, if (status.startsWith("Could not")) Bad else Term)

        val p = project
        if (p == null) {
            NBox(fill = Paper) { EmptyState("No project open", "Pick a folder on this phone.") }
            return@Column
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(240) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Question about the code") },
            placeholder = { Text("e.g. where is the API key checked?") },
            maxLines = 3,
            enabled = !searching,
        )
        NButton("Find code", fill = Term, enabled = query.isNotBlank() && !searching) {
            searching = true
            scope.launch {
                val (found, whole) = withContext(Dispatchers.Default) {
                    val hits = CaseDocuments.retrieve(p.files, query.trim())
                    if (hits.isNotEmpty()) hits to false else CaseDocuments.wholeCase(p.files) to true
                }
                evidence = found
                wholeProject = whole && found.isNotEmpty()
                searching = false
                status = when {
                    found.isEmpty() -> "No code matched. Try a function, class or file name."
                    whole -> "No match · the whole project (${found.size} excerpt(s)) is small enough to ask."
                    else -> "Found ${found.size} excerpt(s)."
                }
            }
        }
        if (wholeProject) Mono("WHOLE PROJECT · no keyword match", 10, Warn)
        evidence.forEach { e ->
            NBox(fill = Paper, pad = 12.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("${e.id} · ${e.fileName}:${e.location.removePrefix("lines ")}")
                    Text(e.text.take(900) + if (e.text.length > 900) "\n…" else "", fontSize = 11.sp, lineHeight = 15.sp,
                        color = Ink, fontFamily = FontFamily.Monospace)
                }
            }
        }
        CitedAsk(runner, onEnsureModel, evidence, query, CaseCitations.codeSystem, busy = searching)
    }
}

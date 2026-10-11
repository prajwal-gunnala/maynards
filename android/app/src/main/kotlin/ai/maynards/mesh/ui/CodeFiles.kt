package ai.maynards.mesh.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

/** A project folder read on this Host. Each file becomes a CaseDocument of 40-line sections. */
data class CodeProject(val name: String, val files: List<CaseDocument>, val skipped: Int, val truncated: Boolean) {
    val lines: Int get() = files.sumOf { f -> f.pages.lastOrNull()?.location?.substringAfter('–')?.toIntOrNull() ?: 0 }
}

/** Reads source files from a folder the user picked. Nothing is copied, executed or sent anywhere. */
object CodeFiles {
    const val MAX_FILES = 400
    const val MAX_FILE_BYTES = 256 * 1024
    const val MAX_TOTAL_CHARS = 3_000_000
    private const val SECTION_LINES = 40

    private val SKIP_DIRS = setOf(".git", "node_modules", "build", "dist", "target", ".gradle", ".idea", ".vscode",
        "vendor", "__pycache__", ".venv", "venv", ".next", "out", "bin", "obj", "Pods", ".dart_tool")
    private val EXTENSIONS = setOf("kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "rs", "go", "c", "h", "cc",
        "cpp", "hpp", "cs", "swift", "rb", "php", "scala", "sh", "sql", "md", "txt", "json", "yaml", "yml", "toml",
        "xml", "gradle", "html", "css", "dart", "lua", "proto")
    private val SKIP_FILES = Regex("(?i)(\\.min\\.(js|css)$|^package-lock\\.json$|^yarn\\.lock$|^Cargo\\.lock$|^\\.env)")

    fun wantedDir(name: String) = name !in SKIP_DIRS && !name.startsWith(".")
    fun wantedFile(name: String) = !SKIP_FILES.containsMatchIn(name) && name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** Splits one file into 40-line sections labelled with their line range. Blank sections are dropped. */
    fun toDocument(path: String, text: String): CaseDocument {
        val lines = text.removePrefix("﻿").replace('\u0000', ' ').lines()
        val pages = lines.chunked(SECTION_LINES).mapIndexed { i, chunk ->
            val first = i * SECTION_LINES + 1
            CasePage("lines $first–${first + chunk.size - 1}", chunk.joinToString("\n").trimEnd())
        }.filter { it.text.isNotBlank() }
        return CaseDocument(path, pages, 0, text.length)
    }

    private data class Row(val id: String, val name: String, val mime: String, val size: Long)

    @Throws(IOException::class)
    fun load(context: Context, tree: Uri): CodeProject {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val files = ArrayList<CaseDocument>()
        var skipped = 0
        var total = 0
        var truncated = false
        val queue = ArrayDeque(listOf(rootId to ""))
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        walk@ while (queue.isNotEmpty()) {
            val (dirId, prefix) = queue.removeFirst()
            val rows = resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId), cols, null, null, null)?.use { c ->
                val out = ArrayList<Row>()
                while (c.moveToNext()) {
                    val size: Long = if (c.isNull(3)) -1L else c.getLong(3)
                    out += Row(c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), size)
                }
                out
            } ?: throw IOException("Android could not list this folder.")
            for (row in rows) {
                val path = if (prefix.isEmpty()) row.name else "$prefix/${row.name}"
                if (row.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (wantedDir(row.name)) queue.addLast(row.id to path)
                    continue
                }
                if (!wantedFile(row.name) || row.size > MAX_FILE_BYTES) { skipped++; continue }
                if (files.size >= MAX_FILES) { truncated = true; break@walk }
                val bytes = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, row.id))
                    ?.use { it.readNBytes(MAX_FILE_BYTES + 1) }
                if (bytes == null || bytes.size > MAX_FILE_BYTES || bytes.take(4096).any { it == 0.toByte() }) { skipped++; continue }
                val text = bytes.toString(Charsets.UTF_8)
                if (total + text.length > MAX_TOTAL_CHARS) { truncated = true; break@walk }
                total += text.length
                files += toDocument(path, text)
            }
        }
        if (files.isEmpty()) throw IOException("No readable source files in this folder.")
        val rootName = tree.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.ifBlank { null } ?: "project"
        return CodeProject(rootName, files.sortedBy { it.name }, skipped, truncated)
    }
}

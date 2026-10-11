package ai.maynards.mesh.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.ln

/** Content lives in memory for the open case-room screen only. No source file is copied to app storage. */
data class CaseDocument(
    val name: String,
    val pages: List<CasePage>,
    val scannedPages: Int,
    val extractedChars: Int,
)

data class CasePage(val location: String, val text: String)

data class CaseEvidence(val id: String, val fileName: String, val location: String, val text: String)

object CaseDocuments {
    private const val MAX_PDF_BYTES = 18 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 4 * 1024 * 1024
    private const val MAX_PDF_PAGES = 120
    private const val MAX_EXTRACTED_CHARS = 500_000
    private const val PASSAGE_CHARS = 1_300

    @Throws(IOException::class)
    fun load(context: Context, uri: Uri): CaseDocument {
        val name = displayName(context, uri)
        if (listOf(".pdf", ".txt", ".md", ".markdown").none { name.endsWith(it, ignoreCase = true) }) {
            throw IOException("$name is not supported yet. Import PDF, TXT, or Markdown files.")
        }
        val isPdf = name.endsWith(".pdf", ignoreCase = true) ||
            context.contentResolver.getType(uri)?.equals("application/pdf", ignoreCase = true) == true
        val bytes = readBounded(context, uri, if (isPdf) MAX_PDF_BYTES else MAX_TEXT_BYTES)
        return if (isPdf) loadPdf(context, name, bytes) else loadText(name, bytes)
    }

    private fun loadPdf(context: Context, name: String, bytes: ByteArray): CaseDocument {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(ByteArrayInputStream(bytes)).use { pdf ->
            if (pdf.numberOfPages > MAX_PDF_PAGES) throw IOException("$name has ${pdf.numberOfPages} pages; the current case-room limit is $MAX_PDF_PAGES.")
            val stripper = PDFTextStripper()
            val pages = ArrayList<CasePage>(pdf.numberOfPages)
            var total = 0
            for (page in 1..pdf.numberOfPages) {
                stripper.startPage = page
                stripper.endPage = page
                val text = stripper.getText(pdf).replace('\u0000', ' ').trim()
                total += text.length
                if (total > MAX_EXTRACTED_CHARS) throw IOException("$name extracts to more than $MAX_EXTRACTED_CHARS characters; select a smaller portion.")
                pages += CasePage("page $page", text)
            }
            val scanned = pages.count { it.text.isBlank() }
            if (pages.all { it.text.isBlank() }) throw IOException("$name has no selectable text. Scanned-page OCR is a later case-room stage.")
            return CaseDocument(name, pages, scanned, total)
        }
    }

    private fun loadText(name: String, bytes: ByteArray): CaseDocument {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").replace('\u0000', ' ')
        if (text.isBlank()) throw IOException("$name is empty or is not readable UTF-8 text.")
        if (text.length > MAX_EXTRACTED_CHARS) throw IOException("$name exceeds the current case-room text limit.")
        val lines = text.lines()
        val pages = lines.chunked(100).mapIndexed { index, chunk ->
            val first = index * 100 + 1
            CasePage("lines $first–${first + chunk.size - 1}", chunk.joinToString("\n").trim())
        }.filter { it.text.isNotBlank() }
        return CaseDocument(name, pages, 0, text.length)
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return (fromProvider ?: uri.lastPathSegment ?: "case-document")
            .substringAfterLast('/')
            .filterNot { it.isISOControl() }
            .take(120)
            .ifBlank { "case-document" }
    }

    private fun readBounded(context: Context, uri: Uri, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > limit) throw IOException("File exceeds the ${limit / (1024 * 1024)} MiB local import limit.")
                out.write(buffer, 0, n)
            }
        } ?: throw IOException("Android could not open this file.")
        return out.toByteArray()
    }

    /** Selects exact page/line passages locally. The original extracted text is never rewritten. */
    fun retrieve(documents: List<CaseDocument>, query: String, maxPassages: Int = 6, maxChars: Int = 5_600): List<CaseEvidence> {
        val passages = documents.flatMap { document ->
            document.pages.flatMap { page -> split(page.text).map { Passage(document.name, page.location, it) } }
        }
        if (passages.isEmpty()) return emptyList()
        val terms = terms(query)
        val docFreq = terms.associateWith { term -> passages.count { p -> terms(p.text).any { same(it, term) } } }
        val ranked = passages.mapIndexed { index, passage ->
            val passageTerms = terms(passage.text)
            val score = terms.sumOf { term ->
                val tf = passageTerms.count { same(it, term) }
                if (tf == 0) 0.0 else {
                    val idf = ln(1.0 + (passages.size - (docFreq[term] ?: 0) + 0.5) / ((docFreq[term] ?: 0) + 0.5))
                    idf * tf / (tf + 1.2)
                }
            }
            Ranked(index, passage, score)
        }.filter { it.score > 0.0 }
            .sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.index })

        val selected = ArrayList<Passage>()
        var size = 0
        for (candidate in ranked) {
            if (selected.size >= maxPassages) break
            if (candidate.passage.text.length > maxChars - size) continue
            selected += candidate.passage
            size += candidate.passage.text.length
        }
        return selected.mapIndexed { index, p -> CaseEvidence("S${(index + 1).toString().padStart(2, '0')}", p.file, p.location, p.text) }
    }

    /**
     * Every passage, in file order, when the whole case fits the answer budget. Used when no keyword
     * matches, so a broad question ("any contradictions?") can still be asked of a small case.
     */
    fun wholeCase(documents: List<CaseDocument>, maxPassages: Int = 6, maxChars: Int = 5_600): List<CaseEvidence> {
        val passages = documents.flatMap { d -> d.pages.flatMap { p -> split(p.text).map { Passage(d.name, p.location, it) } } }
        if (passages.isEmpty() || passages.size > maxPassages || passages.sumOf { it.text.length } > maxChars) return emptyList()
        return passages.mapIndexed { index, p -> CaseEvidence("S${(index + 1).toString().padStart(2, '0')}", p.file, p.location, p.text) }
    }

    /** One word form matches another: signed/sign, agreement/agree, documents/document. */
    private fun same(a: String, b: String): Boolean =
        a == b || (minOf(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a)))

    private fun split(text: String): List<String> {
        if (text.length <= PASSAGE_CHARS) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val hardEnd = minOf(start + PASSAGE_CHARS, text.length)
            if (hardEnd == text.length) { out += text.substring(start).trim(); break }
            val softFloor = start + PASSAGE_CHARS / 2
            val boundary = maxOf(
                text.lastIndexOf('\n', hardEnd - 1),
                text.lastIndexOf(' ', hardEnd - 1),
                text.lastIndexOf('.', hardEnd - 1),
            ).takeIf { it >= softFloor } ?: hardEnd
            val end = if (boundary < hardEnd && text[boundary] == '.') boundary + 1 else boundary
            out += text.substring(start, end).trim()
            start = end.coerceAtLeast(start + 1)
        }
        return out.filter { it.isNotBlank() }
    }

    /** Words, lowercased; a camelCase identifier also yields its parts (ensureUseCaseModel → ensure, use, case, model). */
    private fun terms(text: String): List<String> = Regex("[\\p{L}\\p{N}]{2,}")
        .findAll(text)
        .flatMap { m ->
            val parts = m.value.split(CAMEL).filter { it.length >= 2 }
            if (parts.size > 1) sequenceOf(m.value) + parts.asSequence() else sequenceOf(m.value)
        }
        .map { it.lowercase() }
        .filterNot { it in STOP_WORDS }
        .toList()

    private val CAMEL = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

    private data class Passage(val file: String, val location: String, val text: String)
    private data class Ranked(val index: Int, val passage: Passage, val score: Double)

    private val STOP_WORDS = setOf("the", "and", "for", "that", "with", "from", "this", "then", "than", "into", "their", "they", "them", "was", "were", "are", "has", "have", "had", "not", "but", "all", "any", "its", "our", "you", "your", "who", "what", "when", "where", "which", "will", "shall")
}

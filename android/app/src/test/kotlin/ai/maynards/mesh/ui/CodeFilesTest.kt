package ai.maynards.mesh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeFilesTest {
    @Test fun sourceFilesAreKeptAndNoiseIsSkipped() {
        listOf("Main.kt", "build.gradle.kts", "app.py", "lib.rs", "README.md").forEach { assertTrue(it, CodeFiles.wantedFile(it)) }
        listOf("logo.png", "app.min.js", "package-lock.json", ".env", ".env.local", "model.gguf").forEach { assertFalse(it, CodeFiles.wantedFile(it)) }
        listOf("node_modules", "build", ".git", ".gradle", "target").forEach { assertFalse(it, CodeFiles.wantedDir(it)) }
        assertTrue(CodeFiles.wantedDir("src"))
    }

    @Test fun filesAreSplitIntoLabelledLineRanges() {
        val doc = CodeFiles.toDocument("src/A.kt", (1..95).joinToString("\n") { "val x$it = $it" })
        assertEquals(listOf("lines 1–40", "lines 41–80", "lines 81–95"), doc.pages.map { it.location })
        assertEquals("src/A.kt", doc.name)
        assertTrue(doc.pages[1].text.startsWith("val x41 = 41"))
    }

    @Test fun camelCaseIdentifiersAreFoundFromPlainWords() {
        val project = listOf(
            CodeFiles.toDocument("ui/HostScreen.kt", "private suspend fun ensureUseCaseModel(needsVision: Boolean): String {\n  return pick()\n}"),
            CodeFiles.toDocument("brain/Chat.kt", "class Chat(private val endpoint: String)"),
        )
        val found = CaseDocuments.retrieve(project, "where is the use case model chosen?")
        assertEquals("ui/HostScreen.kt", found.first().fileName)
        assertEquals("lines 1–3", found.first().location)
    }
}

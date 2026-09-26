package ai.maynards.mesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Just enough markdown for chat answers: code blocks, headings, **bold** and `code`. */
@Composable
fun Markdown(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks(text).forEach { (isCode, body) ->
            if (isCode) {
                val shape = RoundedCornerShape(6.dp)
                Text(
                    body.trimEnd(),
                    Modifier.fillMaxWidth().background(Ink, shape).border(2.dp, Ink, shape)
                        .horizontalScroll(rememberScrollState()).padding(10.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Yellow, softWrap = false,
                )
            } else {
                Text(inline(body.trim('\n')), fontSize = 15.sp, color = Ink, lineHeight = 21.sp)
            }
        }
    }
}

/** Splits text into (isCode, body) blocks on ``` fences. An unclosed fence (still streaming) counts as code. */
private fun blocks(text: String): List<Pair<Boolean, String>> {
    val parts = text.split("```")
    return parts.mapIndexedNotNull { i, p ->
        val code = i % 2 == 1
        val body = if (code) p.substringAfter('\n', p) else p      // drop the language tag line
        if (body.isBlank()) null else code to body
    }
}

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { n, raw ->
        if (n > 0) append('\n')
        val heading = raw.trimStart().startsWith("#")
        val line = if (heading) raw.trimStart().trimStart('#').trimStart() else raw
        if (heading) pushStyle(SpanStyle(fontWeight = FontWeight.Black, fontSize = 17.sp))
        var i = 0
        while (i < line.length) {
            when {
                line.startsWith("**", i) && line.indexOf("**", i + 2) > 0 -> {
                    val end = line.indexOf("**", i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(line.substring(i + 2, end)) }
                    i = end + 2
                }
                line[i] == '`' && line.indexOf('`', i + 1) > 0 -> {
                    val end = line.indexOf('`', i + 1)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Cream, fontWeight = FontWeight.SemiBold)) {
                        append(line.substring(i + 1, end))
                    }
                    i = end + 1
                }
                else -> { append(line[i]); i++ }
            }
        }
        if (heading) pop()
    }
}

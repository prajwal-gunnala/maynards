package ai.maynards.mesh.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test fun inlineAndDisplayMathShowTheirInside() {
        assertEquals("To solve 3x + 4 = 19:", stripMath("To solve \$3x + 4 = 19\$:"))
        assertEquals("x = 5", stripMath("\$\$x = 5\$\$"))
        assertEquals("then x", stripMath("then \\(x\\)"))
        assertEquals("2 × 3 · 4", stripMath("\$2 \\times 3 \\cdot 4\$"))
    }

    @Test fun pricesAreLeftAlone() {
        assertEquals("It costs \$5 and \$10.", stripMath("It costs \$5 and \$10."))
        assertEquals("\$5", stripMath("\$5"))
    }
}

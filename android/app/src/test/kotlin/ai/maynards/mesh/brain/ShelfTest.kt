package ai.maynards.mesh.brain

import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfTest {
    @Test fun qwen35QuantizedModelFindsItsVisionProjector() {
        assertEquals("mmproj-Qwen3.5-9B", projectorPrefix("Qwen3.5-9B-Q4_K_M.gguf"))
    }

    @Test fun instructionModelNameIsPreserved() {
        assertEquals("mmproj-Qwen2.5-VL-3B-Instruct", projectorPrefix("Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf"))
    }
}

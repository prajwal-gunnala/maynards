package ai.maynards.mesh.brain

/**
 * Models we know, so the Models page can judge them before they are on the phone.
 * Numbers were read from the real GGUF headers; per-layer sizes are the average block.
 */
object Catalog {
    data class Entry(val info: ModelInfo, val url: String, val vision: Boolean = false)

    private fun entry(file: String, name: String, arch: String, bytes: Long, layers: Int, other: Long, kv: Long, url: String, vision: Boolean = false) =
        Entry(ModelInfo(file, name, arch, bytes, List(layers) { (bytes - other) / layers }, other, kv, 32_768), url, vision)

    val all = listOf(
        entry("Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf", "Qwen3-Coder-30B-A3B-Instruct", "qwen3moe",
            18_556_689_568, 48, 430_290_944, 98_304,
            "https://huggingface.co/unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF/resolve/main/Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf"),
        entry("Qwen3-8B-Q4_K_M.gguf", "Qwen3 8B", "qwen3",
            5_027_783_488, 36, 860_581_888, 147_456,
            "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf"),
        entry("Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf", "Qwen2.5 VL 3B Instruct", "qwen2vl",
            1_929_901_056, 36, 255_260_672, 36_864,
            "https://huggingface.co/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF", vision = true),
        entry("Qwen3-0.6B-Q8_0.gguf", "Qwen3 0.6B Instruct", "qwen3",
            639_446_688, 28, 165_310_464, 114_688,
            "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf"),
    )
}

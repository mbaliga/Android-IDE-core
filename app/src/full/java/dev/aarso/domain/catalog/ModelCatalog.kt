package dev.aarso.domain.catalog

/**
 * FULL (sideload) catalog — the original list, unchanged: best-known abliterated
 * GGUFs at time of writing (mirrors the throwaway Termux prototype's manifest).
 * This landscape moves monthly (§3) — filenames may 404; the custom-URL path is
 * the escape hatch. This catalog ships only in the sideload build.
 */
object ModelCatalog {
    // URLs verified to resolve at build time; HF filenames still drift, so the
    // custom-URL path remains the escape hatch. Ordered small -> large.
    val models: List<CatalogModel> = listOf(
        CatalogModel(
            id = "qwen2.5-1.5b-abliterated-q4",
            name = "Qwen2.5-1.5B (abliterated)",
            family = "qwen2.5", params = "1.5B", quant = "Q4_K_M",
            sizeBytes = 1_100_000_000L, contextWindow = 8192,
            hfRepo = "mradermacher/Qwen2.5-1.5B-Instruct-abliterated-GGUF",
            hfFile = "Qwen2.5-1.5B-Instruct-abliterated.Q4_K_M.gguf",
        ),
        CatalogModel(
            id = "qwen2.5-3b-abliterated-q4",
            name = "Qwen2.5-3B (abliterated)",
            family = "qwen2.5", params = "3B", quant = "Q4_K_M",
            sizeBytes = 2_100_000_000L, contextWindow = 8192,
            hfRepo = "mradermacher/Qwen2.5-3B-Instruct-abliterated-GGUF",
            hfFile = "Qwen2.5-3B-Instruct-abliterated.Q4_K_M.gguf",
        ),
        CatalogModel(
            id = "gemma-3-4b-it-q4",
            name = "Gemma 3 4B (abliterated)",
            family = "gemma3", params = "4B", quant = "Q4_K_M",
            sizeBytes = 2_600_000_000L, contextWindow = 8192,
            hfRepo = "mradermacher/gemma-3-4b-it-abliterated-GGUF",
            hfFile = "gemma-3-4b-it-abliterated.Q4_K_M.gguf",
        ),
        CatalogModel(
            id = "qwen2.5-7b-abliterated-q4",
            name = "Qwen2.5-7B (abliterated v2)",
            family = "qwen2.5", params = "7B", quant = "Q4_K_M",
            sizeBytes = 4_700_000_000L, contextWindow = 8192,
            hfRepo = "mradermacher/Qwen2.5-7B-Instruct-abliterated-v2-GGUF",
            hfFile = "Qwen2.5-7B-Instruct-abliterated-v2.Q4_K_M.gguf",
        ),
        CatalogModel(
            id = "llama-3.1-8b-abliterated-q4",
            name = "Llama 3.1 8B (abliterated)",
            family = "llama3", params = "8B", quant = "Q4_K_M",
            sizeBytes = 4_900_000_000L, contextWindow = 8192,
            hfRepo = "mradermacher/Llama-3.1-8B-Instruct-abliterated-GGUF",
            hfFile = "Llama-3.1-8B-Instruct-abliterated.Q4_K_M.gguf",
        ),
        CatalogModel(
            id = "qwen3-14b-abliterated-q4",
            name = "Qwen3-14B (abliterated)",
            family = "qwen3", params = "14B", quant = "Q4_K_M",
            sizeBytes = 9_000_000_000L, contextWindow = 8192,
            hfRepo = "bartowski/huihui-ai_Qwen3-14B-abliterated-GGUF",
            hfFile = "huihui-ai_Qwen3-14B-abliterated-Q4_K_M.gguf",
        ),
        CatalogModel(
            id = "phi-4-abliterated-q4",
            name = "Phi-4 14B (abliterated)",
            family = "phi4", params = "14B", quant = "Q4_K_M",
            sizeBytes = 9_100_000_000L, contextWindow = 8192,
            hfRepo = "mradermacher/phi-4-abliterated-GGUF",
            hfFile = "phi-4-abliterated.Q4_K_M.gguf",
        ),
        CatalogModel(
            id = "qwen3-30b-a3b-abliterated-q4",
            name = "Qwen3-30B-A3B (abliterated, MoE)",
            family = "qwen3-moe", params = "30B-A3B", quant = "Q4_K_M",
            sizeBytes = 18_000_000_000L, contextWindow = 8192,
            hfRepo = "mradermacher/Qwen3-30B-A3B-abliterated-GGUF",
            hfFile = "Qwen3-30B-A3B-abliterated.Q4_K_M.gguf",
        ),
    )
}

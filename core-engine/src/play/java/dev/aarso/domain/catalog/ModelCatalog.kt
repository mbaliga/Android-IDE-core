package dev.aarso.domain.catalog

/**
 * PLAY catalog — official instruct releases only, from ungated repos (official
 * google/ and meta-llama/ HF repos are license-gated and 401 on direct download,
 * so community single-file mirrors are used where needed). Same escape hatch as
 * always: any custom GGUF URL. Every URL below verified to resolve (HTTP 200)
 * on 2026-06-12. Ordered small -> large.
 */
object ModelCatalog {
    val models: List<CatalogModel> = listOf(
        CatalogModel(
            id = "qwen2.5-1.5b-instruct-q4",
            name = "Qwen2.5-1.5B Instruct",
            family = "qwen2.5", params = "1.5B", quant = "Q4_K_M",
            sizeBytes = 1_100_000_000L, contextWindow = 8192,
            hfRepo = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            hfFile = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        ),
        CatalogModel(
            id = "qwen2.5-3b-instruct-q4",
            name = "Qwen2.5-3B Instruct",
            family = "qwen2.5", params = "3B", quant = "Q4_K_M",
            sizeBytes = 2_100_000_000L, contextWindow = 8192,
            hfRepo = "Qwen/Qwen2.5-3B-Instruct-GGUF",
            hfFile = "qwen2.5-3b-instruct-q4_k_m.gguf",
        ),
        CatalogModel(
            id = "gemma-3-4b-it-q4",
            name = "Gemma 3 4B Instruct",
            family = "gemma3", params = "4B", quant = "Q4_K_M",
            sizeBytes = 2_500_000_000L, contextWindow = 8192,
            hfRepo = "ggml-org/gemma-3-4b-it-GGUF",
            hfFile = "gemma-3-4b-it-Q4_K_M.gguf",
        ),
        CatalogModel(
            id = "qwen2.5-7b-instruct-q4",
            name = "Qwen2.5-7B Instruct",
            family = "qwen2.5", params = "7B", quant = "Q4_K_M",
            sizeBytes = 4_700_000_000L, contextWindow = 8192,
            hfRepo = "bartowski/Qwen2.5-7B-Instruct-GGUF",
            hfFile = "Qwen2.5-7B-Instruct-Q4_K_M.gguf",
        ),
        CatalogModel(
            id = "llama-3.1-8b-instruct-q4",
            name = "Llama 3.1 8B Instruct",
            family = "llama3", params = "8B", quant = "Q4_K_M",
            sizeBytes = 4_900_000_000L, contextWindow = 8192,
            hfRepo = "bartowski/Meta-Llama-3.1-8B-Instruct-GGUF",
            hfFile = "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
        ),
        CatalogModel(
            id = "qwen3-14b-q4",
            name = "Qwen3-14B",
            family = "qwen3", params = "14B", quant = "Q4_K_M",
            sizeBytes = 9_000_000_000L, contextWindow = 8192,
            hfRepo = "Qwen/Qwen3-14B-GGUF",
            hfFile = "Qwen3-14B-Q4_K_M.gguf",
        ),
        CatalogModel(
            id = "phi-4-q4",
            name = "Phi-4 14B",
            family = "phi4", params = "14B", quant = "Q4_K_M",
            sizeBytes = 9_100_000_000L, contextWindow = 8192,
            hfRepo = "bartowski/phi-4-GGUF",
            hfFile = "phi-4-Q4_K_M.gguf",
        ),
    )
}

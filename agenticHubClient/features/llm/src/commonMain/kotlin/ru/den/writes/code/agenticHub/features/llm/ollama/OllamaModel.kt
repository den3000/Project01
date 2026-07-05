package ru.den.writes.code.agenticHub.features.llm.ollama

/**
 * Typed identifier for a locally-served Ollama model.
 *
 * Mirrors [ru.den.writes.code.agenticHub.features.llm.openrouter.OpenRouterModel]
 * in shape: a small [Known] catalog plus a [Custom] escape hatch. Unlike the cloud
 * providers, Ollama tags depend entirely on what the user has pulled locally
 * (`ollama pull <tag>`), so [Custom] is the primary path — [Known] just names a few
 * common generative tags for convenience. Pass any pulled tag via `-model`; unknown
 * ids fall through to [Custom] and go on the wire verbatim.
 *
 * These are GENERATIVE (chat) models — embeddings live in features:rag with their
 * own embedding model (`nomic-embed-text`).
 */
sealed interface OllamaModel {
    val id: String

    /** A few common generative tags. The real roster is whatever is pulled locally. */
    enum class Known(override val id: String) : OllamaModel {
        Gemma4_26b("gemma4:26b"),
        Gemma4_31b("gemma4:31b"),
        Qwen35_27b("qwen3.5:27b"),
    }

    /** Escape hatch for any tag not in [Known] (the common case for local models). */
    data class Custom(override val id: String) : OllamaModel

    companion object {
        /** Default generative tag; override per-run with any pulled tag via `Custom`. */
        val Default: OllamaModel = Known.Gemma4_26b

        /** Resolves a raw tag to a [Known] entry, falling back to [Custom]. */
        fun fromId(id: String): OllamaModel =
            Known.entries.firstOrNull { it.id == id } ?: Custom(id)
    }
}

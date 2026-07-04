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
        Gemma3_4b("gemma3:4b"),
        Gemma3_12b("gemma3:12b"),
        Gemma3_27b("gemma3:27b"),
        Llama33_70b("llama3.3:70b"),
        Qwen3_8b("qwen3:8b"),
    }

    /** Escape hatch for any tag not in [Known] (the common case for local models). */
    data class Custom(override val id: String) : OllamaModel

    companion object {
        /** A light default so a smoke run works on modest hardware. */
        val Default: OllamaModel = Known.Gemma3_4b

        /** Resolves a raw tag to a [Known] entry, falling back to [Custom]. */
        fun fromId(id: String): OllamaModel =
            Known.entries.firstOrNull { it.id == id } ?: Custom(id)
    }
}

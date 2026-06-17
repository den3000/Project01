package ru.den.writes.code.project01.shared.llm

import ru.den.writes.code.project01.shared.llm.gemini.GeminiModel
import ru.den.writes.code.project01.shared.llm.huggingface.HuggingFaceModel
import ru.den.writes.code.project01.shared.llm.openrouter.OpenRouterModel

/**
 * Provider bundle: the minimum needed to route a request — endpoint
 * URL, resolved API key, and the typed model.
 *
 * Exists only as a CLI-layer discriminator between the supported
 * providers (currently Gemini, OpenRouter and Hugging Face). The actual
 * wire shapes, DTOs and per-turn logic live in [GeminiApi] /
 * [OpenRouterApi] / [HuggingFaceApi]; [ModelProvider] is just the
 * carrier that lets [CliArgs] expose a single field and `main.kt`
 * dispatch on a `when`.
 */
sealed interface ModelProvider {
    /** Full URL of the chat-style endpoint for this provider/model. */
    val endpoint: String

    /** Resolved API key. Empty string means "not configured". */
    val apiKey: String

    /** The model id as it will go on the wire. Shortcut for logs/footer. */
    val modelId: String

    /**
     * Gemini's `generateContent` endpoint embeds the model id in the
     * URL path, so the URL depends on the chosen model.
     */
    data class Gemini(
        val model: GeminiModel = GeminiModel.Default,
        override val apiKey: String,
    ) : ModelProvider {
        override val endpoint: String
            get() = "https://generativelanguage.googleapis.com/v1beta/models/${model.id}:generateContent"
        override val modelId: String get() = model.id
    }

    /**
     * OpenRouter uses one endpoint for all models; the model id rides
     * in the request body.
     */
    data class OpenRouter(
        val model: OpenRouterModel = OpenRouterModel.Default,
        override val apiKey: String,
    ) : ModelProvider {
        override val endpoint: String get() = "https://openrouter.ai/api/v1/chat/completions"
        override val modelId: String get() = model.id
    }

    /**
     * Hugging Face Inference Providers — Router endpoint that fronts a
     * pool of backing providers (Cerebras / Together / Fireworks / …).
     * One endpoint for all models; the model id rides in the request
     * body, same as OpenRouter.
     */
    data class HuggingFace(
        val model: HuggingFaceModel = HuggingFaceModel.Default,
        override val apiKey: String,
    ) : ModelProvider {
        override val endpoint: String get() = "https://router.huggingface.co/v1/chat/completions"
        override val modelId: String get() = model.id
    }
}

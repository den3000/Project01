package ru.den.writes.code.project01.shared.llm.openrouter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for OpenRouter's chat completions endpoint. The shape is
 * OpenAI-compatible — OpenRouter accepts the OpenAI SDK as a drop-in
 * client, and the same JSON keys apply here.
 *
 * See: https://openrouter.ai/docs/quickstart
 */
@Serializable
internal data class OpenRouterRequest(
    val model: String,
    val messages: List<OpenRouterMessage>,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stop: List<String>? = null,
)

/**
 * One turn on the wire. [role] is `"system"` / `"user"` / `"assistant"`
 * (OpenAI naming, unlike Gemini's `"model"`).
 */
@Serializable
internal data class OpenRouterMessage(val role: String, val content: String)

@Serializable
internal data class OpenRouterResponse(
    val choices: List<OpenRouterChoice> = emptyList(),
    val usage: OpenRouterUsage? = null,
    /** Present only on failure; mutually exclusive with [choices]. */
    val error: OpenRouterError? = null,
)

@Serializable
internal data class OpenRouterChoice(
    val message: OpenRouterRespMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class OpenRouterRespMessage(val role: String, val content: String?)

/**
 * Token accounting. Unlike Gemini there's no `thoughtsTokenCount` — thinking
 * tokens (when used) are folded into `completion_tokens`.
 */
@Serializable
internal data class OpenRouterUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
internal data class OpenRouterError(val code: Int? = null, val message: String? = null)

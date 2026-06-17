package ru.den.writes.code.project01.shared.llm.huggingface

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for Hugging Face Router's chat completions endpoint. The
 * Router speaks the OpenAI dialect, so the shape mirrors
 * [OpenRouterRequest] one-for-one.
 *
 * See: https://huggingface.co/docs/inference-providers/tasks/chat-completion
 */
@Serializable
internal data class HuggingFaceRequest(
    val model: String,
    val messages: List<HuggingFaceMessage>,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stop: List<String>? = null,
)

/**
 * One turn on the wire. [role] is `"system"` / `"user"` / `"assistant"`
 * — OpenAI naming, same as OpenRouter.
 */
@Serializable
internal data class HuggingFaceMessage(val role: String, val content: String)

@Serializable
internal data class HuggingFaceResponse(
    val choices: List<HuggingFaceChoice> = emptyList(),
    val usage: HuggingFaceUsage? = null,
    /** Present only on failure; mutually exclusive with [choices]. */
    val error: HuggingFaceError? = null,
)

@Serializable
internal data class HuggingFaceChoice(
    val message: HuggingFaceRespMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class HuggingFaceRespMessage(val role: String, val content: String?)

/**
 * Token accounting. Identical to OpenAI's shape, plus an optional
 * [completionTokensDetails] block that some providers (DeepSeek-R1,
 * Qwen3-Thinking, gpt-oss variants…) populate with a separate
 * `reasoning_tokens` count. When absent, [Usage.thoughtsTokens] is 0.
 */
@Serializable
internal data class HuggingFaceUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("completion_tokens_details")
    val completionTokensDetails: CompletionTokensDetails? = null,
)

@Serializable
internal data class CompletionTokensDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int = 0,
)

@Serializable
internal data class HuggingFaceError(val code: Int? = null, val message: String? = null)

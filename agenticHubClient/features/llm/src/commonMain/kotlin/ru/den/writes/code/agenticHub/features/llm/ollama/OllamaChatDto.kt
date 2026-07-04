package ru.den.writes.code.agenticHub.features.llm.ollama

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for Ollama's `POST /api/chat` endpoint. Roughly OpenAI-shaped
 * (`model` + `messages[{role, content}]`), but per-turn knobs (temperature, token
 * cap, stop sequences) go inside a nested [OllamaOptions] object rather than at the
 * top level. [stream] is pinned `false` so the whole reply comes back as one JSON
 * body (no NDJSON streaming to assemble).
 *
 * See: https://github.com/ollama/ollama/blob/main/docs/api.md#generate-a-chat-completion
 */
@Serializable
internal data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = false,
    val options: OllamaOptions? = null,
)

/** One turn on the wire. [role] is `"system"` / `"user"` / `"assistant"` (OpenAI naming). */
@Serializable
internal data class OllamaMessage(val role: String, val content: String)

/**
 * Generation knobs. Ollama names the token cap `num_predict` (not `max_tokens`).
 * All-null → the request omits `options` entirely and Ollama uses model defaults.
 */
@Serializable
internal data class OllamaOptions(
    val temperature: Double? = null,
    @SerialName("num_predict") val numPredict: Int? = null,
    val stop: List<String>? = null,
)

/**
 * Non-streaming chat response. On success [message] carries the reply and the
 * `*_eval_count` fields carry token accounting; on failure Ollama returns a plain
 * [error] string instead.
 */
@Serializable
internal data class OllamaChatResponse(
    val message: OllamaRespMessage? = null,
    val done: Boolean = false,
    @SerialName("prompt_eval_count") val promptEvalCount: Int = 0,
    @SerialName("eval_count") val evalCount: Int = 0,
    /** Present only on failure. */
    val error: String? = null,
)

@Serializable
internal data class OllamaRespMessage(val role: String, val content: String? = null)

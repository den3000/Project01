package ru.den.writes.code.agenticHub.features.llm.ollama

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for Ollama's `POST /api/chat` endpoint. Roughly OpenAI-shaped
 * (`model` + `messages[{role, content}]`), but per-turn knobs (temperature, token
 * cap, stop sequences) go inside a nested [OllamaOptions] object rather than at the
 * top level.
 *
 * [stream] carries NO default on purpose: Ollama streams NDJSON unless it receives an
 * explicit `"stream": false`, and kotlinx JSON (`encodeDefaults = false`) would drop
 * a property left at its declared default — so a `= false` default would silently
 * vanish from the wire and the server would stream. Declaring it defaultless forces
 * it onto every request.
 *
 * See: https://github.com/ollama/ollama/blob/main/docs/api.md#generate-a-chat-completion
 */
@Serializable
internal data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean,
    /**
     * Toggles reasoning on thinking-capable models (gemma4, qwen3.5, …). `null` leaves
     * the model default; `false` disables — important because with thinking ON a small
     * token cap can be spent entirely on the (separate) `thinking` field, leaving
     * `content` empty. Mapped from [GenerationParams.thinkingBudget].
     */
    val think: Boolean? = null,
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
internal data class OllamaRespMessage(
    val role: String,
    val content: String? = null,
    /** Reasoning trace on thinking models (separate from [content]); we surface content. */
    val thinking: String? = null,
)

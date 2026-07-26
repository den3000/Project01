package ru.den.writes.code.agenticHub.features.llm.ollama

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    /**
     * Tool declarations offered to the model, OpenAI-shaped. Omitted (null) for a
     * tool-less request — same bytes on the wire as before tools existed.
     */
    val tools: List<OllamaTool>? = null,
    val options: OllamaOptions? = null,
)

/**
 * OpenAI-shaped declaration wrapper: a `"function"` discriminator plus the callable.
 *
 * [type] carries NO default for the same reason as [OllamaChatRequest.stream] — with
 * `encodeDefaults = false` a property left at its declared default is dropped from the
 * body, and the discriminator would silently vanish from every tool entry.
 */
@Serializable
internal data class OllamaTool(val type: String, val function: OllamaFunction)

/**
 * One callable function: [name], a [description] the model reads to decide when to
 * call it, and a JSON-Schema [parameters] object describing the inputs.
 */
@Serializable
internal data class OllamaFunction(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject? = null,
)

/**
 * One turn on the wire. [role] is `"system"` / `"user"` / `"assistant"` / `"tool"`
 * (OpenAI naming).
 *
 * [toolCalls] is set only on an assistant turn replayed back as a tool invocation
 * (its [content] is then empty); [toolName] only on a `"tool"` turn carrying that
 * tool's output. Both omitted on an ordinary turn.
 */
@Serializable
internal data class OllamaMessage(
    val role: String,
    val content: String,
    @SerialName("tool_calls") val toolCalls: List<OllamaToolCall>? = null,
    @SerialName("tool_name") val toolName: String? = null,
)

/** A tool invocation, in either direction: the model's request or our replay of it. */
@Serializable
internal data class OllamaToolCall(val function: OllamaToolCallFunction)

/**
 * The invoked function's [name] and the [arguments] the model supplied.
 *
 * [arguments] is a [JsonElement], not a [JsonObject], on purpose: Ollama normally
 * sends an object, but some builds (and some models) hand back the same object
 * JSON-encoded as a string. Typing it strictly would fail the whole response parse
 * on those; [argumentsObject] does the narrowing instead.
 */
@Serializable
internal data class OllamaToolCallFunction(
    val name: String,
    val arguments: JsonElement = JsonObject(emptyMap()),
)

/**
 * [OllamaToolCallFunction.arguments] narrowed to an object: passed through when it
 * already is one, parsed when it arrives as a JSON string. An unparsable or
 * non-object value yields an empty object — a tool call with no arguments is
 * something the executor can answer (with a validation error from the tool itself),
 * whereas a thrown parse error would kill the whole turn.
 */
internal fun OllamaToolCallFunction.argumentsObject(): JsonObject {
    val raw = arguments
    if (raw is JsonObject) return raw
    val encoded = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return JsonObject(emptyMap())
    return runCatching { argumentsJson.parseToJsonElement(encoded) as? JsonObject }.getOrNull()
        ?: JsonObject(emptyMap())
}

/** Parser for the string-encoded [OllamaToolCallFunction.arguments] form. */
private val argumentsJson = Json

/**
 * Generation knobs. Ollama names the token cap `num_predict` (not `max_tokens`)
 * and the context window `num_ctx`. All-null → the request omits `options`
 * entirely and Ollama uses model defaults.
 */
@Serializable
internal data class OllamaOptions(
    val temperature: Double? = null,
    @SerialName("num_predict") val numPredict: Int? = null,
    val stop: List<String>? = null,
    @SerialName("num_ctx") val numCtx: Int? = null,
    @SerialName("top_p") val topP: Double? = null,
    val seed: Int? = null,
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
    /**
     * Tools the model asked to call instead of (or alongside) answering. Absent on a
     * plain reply and on models served without tool support.
     */
    @SerialName("tool_calls") val toolCalls: List<OllamaToolCall>? = null,
)

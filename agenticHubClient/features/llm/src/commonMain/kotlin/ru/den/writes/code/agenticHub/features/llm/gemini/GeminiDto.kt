package ru.den.writes.code.agenticHub.features.llm.gemini

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class GeminiRequest(
    val contents: List<Content>,
    val tools: List<GeminiTool>? = null,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: SystemInstruction? = null,
)

/**
 * Gemini groups callable functions under `tools[].functionDeclarations`. We send
 * one tool entry holding all declarations. Omitted from the body when the caller
 * passes no tools — same bytes as before for tool-less requests.
 */
@Serializable
internal data class GeminiTool(val functionDeclarations: List<FunctionDeclaration>)

/**
 * One callable function the model may invoke: [name], a [description] it reads to
 * decide when to call, and a JSON-Schema [parameters] object.
 */
@Serializable
internal data class FunctionDeclaration(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject? = null,
)

/**
 * Developer-supplied directive sent alongside [GeminiRequest.contents].
 * Used here to ask the model to end its response with a specific trailing
 * marker (best-effort — not a guarantee). See
 * https://ai.google.dev/api/generate-content#v1beta.GenerateContentRequest
 */
@Serializable
internal data class SystemInstruction(val parts: List<Part>)

/**
 * One turn in the conversation. [role] is `"user"` or `"model"` and is
 * required for multi-turn requests — Gemini uses it to know who said what
 * when the client resends the running history. May be left null for one-shot
 * single-turn calls; the server defaults the lone entry to `user`.
 */
@Serializable
internal data class Content(
    // Defaulted so a response candidate that carries content WITHOUT parts —
    // Gemini does this when it stops with no output (MAX_TOKENS spent on
    // thinking, SAFETY, …) — deserialises instead of throwing MissingField and
    // killing the whole call. A no-parts reply is then handled as "no content"
    // (see GeminiApi.noContentError), not as a crash. Requests always set parts.
    val parts: List<Part> = emptyList(),
    val role: String? = null,
)

/**
 * A content part: exactly one of [text] (ordinary turn), [functionCall] (the
 * model invoking a tool) or [functionResponse] (a tool result handed back). All
 * nullable + omit-nulls so a text-only part serialises byte-identically to before.
 */
@Serializable
internal data class Part(
    val text: String? = null,
    val functionCall: FunctionCall? = null,
    val functionResponse: FunctionResponse? = null,
)

/** Model → app: the tool [name] and the [args] object the model supplied. */
@Serializable
internal data class FunctionCall(
    val name: String,
    val args: JsonObject = JsonObject(emptyMap()),
)

/** App → model: a tool result. Gemini requires [response] to be an object. */
@Serializable
internal data class FunctionResponse(
    val name: String,
    val response: JsonObject,
)

/**
 * Subset of Gemini's `generationConfig` we expose. Null fields are omitted
 * from the JSON payload (see [Json.explicitNulls] in main.kt).
 */
@Serializable
internal data class GenerationConfig(
    val maxOutputTokens: Int? = null,
    val stopSequences: List<String>? = null,
    val temperature: Double? = null,
    val thinkingConfig: ThinkingConfig? = null,
)

/**
 * Gemini `thinkingConfig`. [thinkingBudget] caps reasoning tokens; 0 disables
 * thinking on Gemini 2.5 Flash so the budget isn't spent before the answer.
 * Omitted from the body (null) leaves the model's default.
 */
@Serializable
internal data class ThinkingConfig(
    val thinkingBudget: Int? = null,
)

@Serializable
internal data class GeminiResponse(
    val candidates: List<Candidate> = emptyList(),
    val usageMetadata: UsageMetadata? = null,
)

/**
 * One generated candidate. [content] is null when the model stopped without
 * producing any (a bare `finishReason` with no `content`), and [finishReason]
 * names why generation ended — `STOP` on a normal end, `MAX_TOKENS` when the
 * budget ran out (often on thinking), `SAFETY` / `RECITATION` when blocked.
 * Surfaced so a "no content" outcome can say the cause instead of an empty text.
 */
@Serializable
internal data class Candidate(
    val content: Content? = null,
    val finishReason: String? = null,
)

/**
 * Token accounting Gemini attaches to each response. All fields are nullable
 * because the API may omit some of them: e.g. `thoughtsTokenCount` only shows
 * up for thinking-capable models, and `cachedContentTokenCount` only when
 * context caching is in use.
 */
@Serializable
internal data class UsageMetadata(
    val promptTokenCount: Int? = null,
    val candidatesTokenCount: Int? = null,
    val thoughtsTokenCount: Int? = null,
    val totalTokenCount: Int? = null,
)

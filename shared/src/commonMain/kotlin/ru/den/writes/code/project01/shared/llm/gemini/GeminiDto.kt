package ru.den.writes.code.project01.shared.llm.gemini

import kotlinx.serialization.Serializable

@Serializable
internal data class GeminiRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: SystemInstruction? = null,
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
    val parts: List<Part>,
    val role: String? = null,
)

@Serializable
internal data class Part(val text: String)

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

@Serializable
internal data class Candidate(val content: Content? = null)

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

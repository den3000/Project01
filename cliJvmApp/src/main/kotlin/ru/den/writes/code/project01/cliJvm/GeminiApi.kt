package ru.den.writes.code.project01.cliJvm

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

@Serializable
internal data class Content(val parts: List<Part>)

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
)

@Serializable
internal data class GeminiResponse(val candidates: List<Candidate> = emptyList())

@Serializable
internal data class Candidate(val content: Content? = null)

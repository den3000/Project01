package ru.den.writes.code.project01.cliJvm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.measureTimedValue

private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
private fun endpointFor(model: GeminiModel): String = "$API_BASE/${model.id}:generateContent"

/**
 * Gemini-backed [LlmApi] implementation.
 *
 * Owns everything Gemini-specific: the [httpClient] used for transport,
 * the [apiKey] appended to every request, the [model] id baked into the
 * URL path, and the mapping between neutral [Message] / [GenerationParams]
 * and Gemini's wire DTOs ([Content], [GenerationConfig], [SystemInstruction]).
 *
 * The instance does not close [httpClient] — lifetime stays with the
 * caller (see `main.kt`, which keeps a single client for the whole
 * process via `HttpClient(Java).use { ... }`).
 *
 * One instance is bound to one model. To talk to a different model,
 * create another [GeminiApi]; the same [httpClient] can safely be
 * shared across them.
 */
internal class GeminiApi(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: GeminiModel = GeminiModel.Default,
) : LlmApi {
    override suspend fun send(
        messages: List<Message>,
        params: GenerationParams,
    ): String? {
        println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
        // Show only the last user turn — the rest of the history is too
        // noisy to print on every call, and that's the prompt the user
        // just typed.
        println("prompt: ${messages.lastOrNull()?.text ?: ""}")
        println("model: ${model.id}")
        params.maxTokens?.let { println("maxTokens: $it") }
        params.stopSequences?.let { println("stopSequences: $it") }
        params.endSequence?.let { println("endSequence: $it") }
        params.temperature?.let { println("temperature: $it") }
        println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")

        try {
            // measureTimedValue wraps the suspend call; the lambda inherits
            // this function's suspend context (it's inline), so we get
            // wall-clock for the actual HTTP round-trip including streaming
            // of the body.
            val (httpResponse, duration) = measureTimedValue {
                httpClient.post(endpointFor(model)) {
                    url { parameters.append("key", apiKey) }
                    contentType(ContentType.Application.Json)
                    setBody(
                        GeminiRequest(
                            contents = messages.map { it.toContent() },
                            generationConfig = params.toGenerationConfig(),
                            systemInstruction = params.toSystemInstruction(),
                        )
                    )
                }
            }

            if (!httpResponse.status.isSuccess()) {
                System.err.println("Gemini API error ${httpResponse.status}: ${httpResponse.bodyAsText()}")
                return null
            }

            val response: GeminiResponse = httpResponse.body()
            val text = response.candidates
                .firstOrNull()?.content?.parts
                ?.joinToString(separator = "") { it.text }
                ?.takeIf { it.isNotBlank() }

            println(text ?: "<empty response>")
            println("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
            println("duration: ${duration.inWholeMilliseconds} ms")
            response.usageMetadata?.let { u ->
                println(
                    "tokens: prompt=${u.promptTokenCount ?: 0}" +
                        ", output=${u.candidatesTokenCount ?: 0}" +
                        (u.thoughtsTokenCount?.let { ", thoughts=$it" } ?: "") +
                        ", total=${u.totalTokenCount ?: 0}"
                )
            }
            println("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
            return text
        } catch (e: Exception) {
            System.err.println("Request failed: ${e.message}")
        }

        return null
    }
}

/** Map a neutral [Message] into Gemini's wire [Content]. */
private fun Message.toContent(): Content =
    Content(
        parts = listOf(Part(text)),
        role = when (role) {
            Role.USER -> "user"
            Role.ASSISTANT -> "model"
        },
    )

/**
 * Builds Gemini's `generationConfig` from the neutral [GenerationParams].
 * Only [GenerationParams.stopSequences] / [maxTokens] / [temperature] land
 * here — [GenerationParams.endSequence] is a different concept (best-effort
 * trailing marker) and is handled via [toSystemInstruction]. Returns `null`
 * when nothing is set so the field is omitted from the request body.
 */
private fun GenerationParams.toGenerationConfig(): GenerationConfig? {
    if (maxTokens == null && stopSequences == null && temperature == null) return null
    return GenerationConfig(
        maxOutputTokens = maxTokens,
        stopSequences = stopSequences,
        temperature = temperature,
    )
}

/**
 * Translates [GenerationParams.endSequence] into a `systemInstruction`
 * asking the model to terminate its response with that exact string.
 * Gemini has no native "force-end-with" field, so this is the closest
 * API-level mechanism; compliance is best-effort, not guaranteed.
 */
private fun GenerationParams.toSystemInstruction(): SystemInstruction? =
    endSequence?.let { end ->
        SystemInstruction(
            parts = listOf(Part("Always end your response with the literal text: \"$end\""))
        )
    }

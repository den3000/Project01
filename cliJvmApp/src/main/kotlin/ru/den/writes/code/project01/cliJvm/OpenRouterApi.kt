package ru.den.writes.code.project01.cliJvm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.measureTimedValue

private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

/**
 * OpenRouter-backed [LlmApi] implementation. OpenRouter speaks the
 * OpenAI chat-completions dialect, so the wire shape differs from
 * [GeminiApi] but the neutral [Message] / [GenerationParams] contract
 * is identical.
 *
 * One instance is bound to one model — see [GeminiApi]'s notes on
 * sharing the [httpClient].
 *
 * Token accounting prints prompt/completion/total only; there is no
 * dedicated «thoughts» counter on this API (thinking tokens, when
 * any, are rolled into `completion_tokens`).
 */
internal class OpenRouterApi(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: OpenRouterModel = OpenRouterModel.Default,
) : LlmApi {
    override suspend fun send(
        messages: List<Message>,
        params: GenerationParams,
    ): String? {
        println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
        println("prompt: ${messages.lastOrNull()?.text ?: ""}")
        println("model: ${model.id}")
        params.maxTokens?.let { println("maxTokens: $it") }
        params.stopSequences?.let { println("stopSequences: $it") }
        params.endSequence?.let { println("endSequence: $it") }
        params.temperature?.let { println("temperature: $it") }
        println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")

        // endSequence has no native field here — same trick as Gemini,
        // lower it to a system message asking the model to terminate
        // with the literal text. Best-effort, not enforced.
        val systemPrefix = params.endSequence?.let {
            listOf(
                OpenRouterMessage(
                    role = "system",
                    content = "Always end your response with the literal text: \"$it\"",
                )
            )
        }.orEmpty()
        val wireMessages = systemPrefix + messages.map { it.toApi() }

        try {
            val (httpResponse, duration) = measureTimedValue {
                httpClient.post(ENDPOINT) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    // Optional, used by OpenRouter for usage analytics on
                    // their rankings page. Send them so this app shows up
                    // as itself rather than as "unknown".
                    header("HTTP-Referer", "https://github.com/denwritescode/Project01")
                    header("X-Title", "Project01 CLI")
                    contentType(ContentType.Application.Json)
                    setBody(
                        OpenRouterRequest(
                            model = model.id,
                            messages = wireMessages,
                            temperature = params.temperature,
                            maxTokens = params.maxTokens,
                            stop = params.stopSequences,
                        )
                    )
                }
            }

            if (!httpResponse.status.isSuccess()) {
                System.err.println("OpenRouter API error ${httpResponse.status}: ${httpResponse.bodyAsText()}")
                return null
            }

            val response: OpenRouterResponse = httpResponse.body()
            response.error?.let {
                System.err.println("OpenRouter error: ${it.message ?: "(no message)"}")
                return null
            }

            val text = response.choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
            println(text ?: "<empty response>")
            println("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
            println("duration: ${duration.inWholeMilliseconds} ms")
            response.usage?.let { u ->
                println(
                    "tokens: prompt=${u.promptTokens}" +
                        ", output=${u.completionTokens}" +
                        ", total=${u.totalTokens}"
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

/** Map a neutral [Message] into the OpenAI-style role/content shape. */
private fun Message.toApi(): OpenRouterMessage =
    OpenRouterMessage(
        role = when (role) {
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
        },
        content = text,
    )

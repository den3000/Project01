package ru.den.writes.code.project01.shared.llm.openrouter

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
import ru.den.writes.code.project01.shared.llm.GenerationParams
import ru.den.writes.code.project01.shared.llm.LlmApi
import ru.den.writes.code.project01.shared.llm.LlmResult
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.llm.Usage

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
 * Token accounting reports prompt/output/total only; there is no
 * dedicated «thoughts» counter on this API (thinking tokens, when
 * any, are rolled into `completion_tokens`). The response footer
 * (`duration`, `tokens: …`) is the [SessionLoop]'s job — it has the
 * running totals that the per-turn footer needs to reference.
 */
class OpenRouterApi(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: OpenRouterModel = OpenRouterModel.Default,
) : LlmApi {
    override suspend fun send(
        messages: List<Message>,
        params: GenerationParams,
    ): LlmResult {
        println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
        println("prompt: ${messages.lastOrNull()?.text ?: ""}")
        println("model: ${model.id}")
        params.maxTokens?.let { println("maxTokens: $it") }
        params.stopSequences?.let { println("stopSequences: $it") }
        params.endSequence?.let { println("endSequence: $it") }
        params.temperature?.let { println("temperature: $it") }
        println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")

        val wireMessages = buildOpenRouterWireMessages(messages, params.endSequence)

        return try {
            val httpResponse = httpClient.post(ENDPOINT) {
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

            if (!httpResponse.status.isSuccess()) {
                val body = httpResponse.bodyAsText().take(500)
                return LlmResult(
                    text = null,
                    error = "OpenRouter API ${httpResponse.status}: $body",
                )
            }

            val response: OpenRouterResponse = httpResponse.body()
            response.error?.let {
                return LlmResult(
                    text = null,
                    error = "OpenRouter error: ${it.message ?: "(no message)"}",
                )
            }

            val text = response.choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
            val usage = response.usage?.let { u ->
                Usage(
                    promptTokens = u.promptTokens,
                    outputTokens = u.completionTokens,
                    thoughtsTokens = 0,
                    totalTokens = u.totalTokens,
                )
            }
            LlmResult(text = text, usage = usage)
        } catch (e: Exception) {
            LlmResult(text = null, error = "Request failed: ${e.message}")
        }
    }
}

/** Map a neutral non-SYSTEM [Message] into the OpenAI-style role/content shape. */
private fun Message.toApi(): OpenRouterMessage =
    OpenRouterMessage(
        role = when (role) {
            // Filtered out by buildOpenRouterWireMessages — SYSTEM goes
            // into the single combined system message, not a per-message
            // wire row. Branch kept for exhaustiveness.
            Role.SYSTEM -> "system"
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
        },
        content = text,
    )

/**
 * Build the OAI-style `messages` list per the [LlmApi.send] contract:
 *
 * - Every `Role.SYSTEM` entry's text is collected (in input order),
 *   joined with `"\n\n"` separators, and an `endSequence` instruction
 *   (when set) is appended with the same separator — producing ONE
 *   `role:"system"` message at the head of the list (canonical
 *   OpenAI-shape pattern; multiple system messages are accepted by
 *   OpenRouter, but a single combined block keeps the prompt
 *   unambiguous on downstream models).
 * - No system message is emitted when both inputs are empty (the
 *   byte-identical fallback for callers that only ever passed
 *   USER/ASSISTANT and no `endSequence`).
 * - Non-SYSTEM entries follow in their original order, mapped through
 *   [toApi].
 */
internal fun buildOpenRouterWireMessages(
    messages: List<Message>,
    endSequence: String?,
): List<OpenRouterMessage> {
    val systemTexts = buildList {
        addAll(messages.filter { it.role == Role.SYSTEM }.map { it.text })
        endSequence?.let { add("Always end your response with the literal text: \"$it\"") }
    }
    val systemPrefix = if (systemTexts.isEmpty()) emptyList()
    else listOf(OpenRouterMessage(role = "system", content = systemTexts.joinToString("\n\n")))
    val turnMsgs = messages.filter { it.role != Role.SYSTEM }
    return systemPrefix + turnMsgs.map { it.toApi() }
}

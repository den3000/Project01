package ru.den.writes.code.agenticHub.features.llm.ollama

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import ru.den.writes.code.agenticHub.features.llm.GenerationParams
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.LlmResult
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.llm.Usage

private const val CHAT_PATH = "/api/chat"

/**
 * [LlmApi] backed by a local Ollama server (`POST /api/chat`, non-streaming). Ollama
 * speaks a roughly OpenAI-shaped chat dialect, so the wire mapping mirrors
 * [ru.den.writes.code.agenticHub.features.llm.openrouter.OpenRouterApi]; the neutral
 * [Message] / [GenerationParams] contract is identical. No API key — the model runs
 * locally (swap [baseUrl] to reach a remote VPS). The [httpClient] is injected
 * (engine + `ContentNegotiation(Json)` come from platform:network), same pattern as
 * the other provider APIs, minus credentials.
 *
 * Token accounting maps `prompt_eval_count` → prompt and `eval_count` → output; there
 * is no separate «thoughts» counter. One instance is bound to one [model].
 */
public class LocalOllamaApi(
    private val httpClient: HttpClient,
    private val model: OllamaModel = OllamaModel.Default,
    private val baseUrl: String = "http://localhost:11434",
) : LlmApi {
    override suspend fun send(
        messages: List<Message>,
        params: GenerationParams,
    ): LlmResult {
        val wireMessages = buildOllamaWireMessages(messages, params.endSequence)

        return try {
            val httpResponse = httpClient.post("$baseUrl$CHAT_PATH") {
                contentType(ContentType.Application.Json)
                setBody(
                    OllamaChatRequest(
                        model = model.id,
                        messages = wireMessages,
                        stream = false,
                        // Reuse the neutral thinking knob: 0 disables, >0 enables, null = model default.
                        think = params.thinkingBudget?.let { it > 0 },
                        options = params.toOllamaOptions(),
                    )
                )
            }

            if (!httpResponse.status.isSuccess()) {
                val body = httpResponse.bodyAsText().take(500)
                return LlmResult(text = null, error = "Ollama API ${httpResponse.status}: $body")
            }

            val response: OllamaChatResponse = httpResponse.body()
            response.error?.let {
                return LlmResult(text = null, error = "Ollama error: $it")
            }

            val text = response.message?.content?.takeIf { it.isNotBlank() }
            val usage = Usage(
                promptTokens = response.promptEvalCount,
                outputTokens = response.evalCount,
                thoughtsTokens = 0,
                totalTokens = response.promptEvalCount + response.evalCount,
            )
            LlmResult(text = text, usage = usage)
        } catch (e: Exception) {
            LlmResult(text = null, error = "Request failed: ${e.message}")
        }
    }
}

/** Fold the neutral params into Ollama's nested `options`; null when nothing is set. */
private fun GenerationParams.toOllamaOptions(): OllamaOptions? {
    if (temperature == null && maxTokens == null && stopSequences == null &&
        contextWindow == null && topP == null && seed == null
    ) {
        return null
    }
    return OllamaOptions(
        temperature = temperature,
        numPredict = maxTokens,
        stop = stopSequences,
        numCtx = contextWindow,
        topP = topP,
        seed = seed,
    )
}

/**
 * Map a neutral non-SYSTEM [Message] into the OpenAI-style role/content shape.
 *
 * Function-calling turns get their own shape, and it is driven by the tool fields
 * rather than [Message.role]: a message carrying [Message.toolCalls] becomes a
 * `role:"assistant"` turn with `tool_calls`, and one carrying [Message.toolResultFor]
 * becomes a `role:"tool"` turn naming the tool whose output sits in [Message.text].
 * The latter arrives as [Role.USER] (that is how the responder replays a result), so
 * reading the role instead would send the result back as an ordinary user turn and
 * the model would lose the link to its own call. Plain turns are unchanged.
 */
private fun Message.toApi(): OllamaMessage {
    toolCalls?.let { calls ->
        return OllamaMessage(
            role = "assistant",
            content = text,
            toolCalls = calls.map { OllamaToolCall(OllamaToolCallFunction(name = it.name, arguments = it.arguments)) },
        )
    }
    toolResultFor?.let { name ->
        return OllamaMessage(role = "tool", content = text, toolName = name)
    }
    return OllamaMessage(
        role = when (role) {
            // Filtered out by buildOllamaWireMessages — SYSTEM goes into the single
            // combined system message, not a per-message wire row. Kept for exhaustiveness.
            Role.SYSTEM -> "system"
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
        },
        content = text,
    )
}

/**
 * Build the OAI-style `messages` list per the [LlmApi.send] contract — identical rules
 * to the OpenRouter/HuggingFace mappers:
 *
 * - Every `Role.SYSTEM` entry's text is collected (in input order), joined with
 *   `"\n\n"`, and an `endSequence` instruction (when set) is appended with the same
 *   separator — producing ONE `role:"system"` message at the head.
 * - No system message is emitted when both inputs are empty.
 * - Non-SYSTEM entries follow in their original order, mapped through [toApi] —
 *   including the function-calling turns (the model's call and the tool's result),
 *   which keep their position in the exchange like any other turn.
 */
internal fun buildOllamaWireMessages(
    messages: List<Message>,
    endSequence: String?,
): List<OllamaMessage> {
    val systemTexts = buildList {
        addAll(messages.filter { it.role == Role.SYSTEM }.map { it.text })
        endSequence?.let { add("Always end your response with the literal text: \"$it\"") }
    }
    val systemPrefix = if (systemTexts.isEmpty()) emptyList()
    else listOf(OllamaMessage(role = "system", content = systemTexts.joinToString("\n\n")))
    val turnMsgs = messages.filter { it.role != Role.SYSTEM }
    return systemPrefix + turnMsgs.map { it.toApi() }
}

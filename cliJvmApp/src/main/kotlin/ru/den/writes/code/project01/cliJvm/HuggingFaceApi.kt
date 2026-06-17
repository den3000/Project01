package ru.den.writes.code.project01.cliJvm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import ru.den.writes.code.project01.shared.llm.GenerationParams
import ru.den.writes.code.project01.shared.llm.HuggingFaceModel
import ru.den.writes.code.project01.shared.llm.LlmApi
import ru.den.writes.code.project01.shared.llm.LlmResult
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.llm.Usage

private const val ENDPOINT = "https://router.huggingface.co/v1/chat/completions"

/**
 * Fallback wait when a 503 (model cold-starting) response carries no
 * `Retry-After` header. Short enough not to feel like the tool froze,
 * long enough to give the backing provider a chance to finish loading.
 */
private const val DEFAULT_503_BACKOFF_MS: Long = 2_000L

/** Cap on the honoured `Retry-After` value — bounds tail latency. */
private const val MAX_503_BACKOFF_MS: Long = 5_000L

/**
 * Hugging Face Router-backed [LlmApi] implementation. The Router speaks
 * the OpenAI chat-completions dialect, so the wire shape matches
 * [OpenRouterApi] — but with two HF-specific wrinkles:
 *
 * 1. Serverless models on the Router can answer the first request with
 *    503 while the backing provider spins up the weights. One retry,
 *    honouring the `Retry-After` header (clamped to [MAX_503_BACKOFF_MS],
 *    [DEFAULT_503_BACKOFF_MS] when absent or unparseable).
 * 2. Reasoning-capable models (DeepSeek-R1, Qwen3-Thinking, gpt-oss…)
 *    optionally surface a `completion_tokens_details.reasoning_tokens`
 *    sub-count. When present, fold it into [Usage.thoughtsTokens] so
 *    pricing can bill it at the output rate (mirroring how
 *    [GeminiApi] treats `thoughtsTokenCount`).
 *
 * One instance is bound to one model — see [GeminiApi]'s notes on
 * sharing the [httpClient].
 */
internal class HuggingFaceApi(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: HuggingFaceModel = HuggingFaceModel.Default,
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

        val wireMessages = buildHuggingFaceWireMessages(messages, params.endSequence)

        // Single-retry loop: at most one extra attempt on 503 (cold start).
        // Everything else (network errors, 4xx, other 5xx) fails immediately.
        var hasRetried = false
        while (true) {
            val httpResponse = try {
                httpClient.post(ENDPOINT) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(
                        HuggingFaceRequest(
                            model = model.id,
                            messages = wireMessages,
                            temperature = params.temperature,
                            maxTokens = params.maxTokens,
                            stop = params.stopSequences,
                        )
                    )
                }
            } catch (e: Exception) {
                return LlmResult(text = null, error = "Request failed: ${e.message}")
            }

            if (httpResponse.status == HttpStatusCode.ServiceUnavailable) {
                if (hasRetried) {
                    val body = httpResponse.bodyAsText().take(500)
                    return LlmResult(
                        text = null,
                        error = "Hugging Face API 503 (after retry): $body",
                    )
                }
                val retryAfter = httpResponse.headers[HttpHeaders.RetryAfter]
                val waitMs = parseRetryAfterSeconds(retryAfter)
                    ?.times(1000L)
                    ?.coerceIn(0L, MAX_503_BACKOFF_MS)
                    ?: DEFAULT_503_BACKOFF_MS
                System.err.println("[retry] 503 (cold start) — waiting ${waitMs}ms, retrying once")
                delay(waitMs)
                hasRetried = true
                continue
            }

            if (!httpResponse.status.isSuccess()) {
                val body = httpResponse.bodyAsText().take(500)
                return LlmResult(
                    text = null,
                    error = "Hugging Face API ${httpResponse.status}: $body",
                )
            }

            val response: HuggingFaceResponse = httpResponse.body()
            response.error?.let {
                return LlmResult(
                    text = null,
                    error = "Hugging Face error: ${it.message ?: "(no message)"}",
                )
            }

            val text = response.choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
            val usage = response.usage?.toNeutral()
            return LlmResult(text = text, usage = usage)
        }
    }
}

/**
 * Parse a Retry-After header value as a non-negative integer number of
 * seconds. HF returns plain integers in practice; HTTP-date form is not
 * supported here (would require parsing RFC 7231 dates for one rare
 * code path — overkill, callers fall back to the default).
 */
internal fun parseRetryAfterSeconds(headerValue: String?): Long? {
    val trimmed = headerValue?.trim() ?: return null
    val seconds = trimmed.toLongOrNull() ?: return null
    return seconds.coerceAtLeast(0L)
}

/** Map a neutral non-SYSTEM [Message] into the OpenAI-style role/content shape. */
private fun Message.toApi(): HuggingFaceMessage =
    HuggingFaceMessage(
        role = when (role) {
            // Filtered out by buildHuggingFaceWireMessages — branch kept
            // for exhaustiveness.
            Role.SYSTEM -> "system"
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
        },
        content = text,
    )

/**
 * Build the OAI-style `messages` list per the [LlmApi.send] contract —
 * see [buildOpenRouterWireMessages] for the rationale. Mirrored here
 * because the HF Router speaks the same dialect but with its own
 * `HuggingFaceMessage` DTO.
 */
internal fun buildHuggingFaceWireMessages(
    messages: List<Message>,
    endSequence: String?,
): List<HuggingFaceMessage> {
    val systemTexts = buildList {
        addAll(messages.filter { it.role == Role.SYSTEM }.map { it.text })
        endSequence?.let { add("Always end your response with the literal text: \"$it\"") }
    }
    val systemPrefix = if (systemTexts.isEmpty()) emptyList()
    else listOf(HuggingFaceMessage(role = "system", content = systemTexts.joinToString("\n\n")))
    val turnMsgs = messages.filter { it.role != Role.SYSTEM }
    return systemPrefix + turnMsgs.map { it.toApi() }
}

/**
 * Collapse HF's OpenAI-shaped usage into the neutral [Usage]. The
 * `reasoning_tokens` sub-count, when present, lands in [Usage.thoughtsTokens]
 * so pricing logic can bill it at the output rate — and to keep the
 * footer's `tokens: in/out/think` column meaningful for thinking models.
 *
 * Note that HF (like OpenAI) already includes reasoning tokens in
 * `completion_tokens`; we subtract them out so `outputTokens` is the
 * visible-text-only count, matching how [GeminiApi] splits the two.
 */
internal fun HuggingFaceUsage.toNeutral(): Usage {
    val reasoning = completionTokensDetails?.reasoningTokens ?: 0
    return Usage(
        promptTokens = promptTokens,
        outputTokens = (completionTokens - reasoning).coerceAtLeast(0),
        thoughtsTokens = reasoning,
        totalTokens = totalTokens,
    )
}

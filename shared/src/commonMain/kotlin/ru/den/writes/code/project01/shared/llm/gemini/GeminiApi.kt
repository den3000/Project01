package ru.den.writes.code.project01.shared.llm.gemini

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import ru.den.writes.code.project01.shared.llm.GenerationParams
import ru.den.writes.code.project01.shared.llm.LlmApi
import ru.den.writes.code.project01.shared.llm.LlmResult
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.llm.Usage
import ru.den.writes.code.project01.shared.util.logWarn

private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
private fun endpointFor(model: GeminiModel): String = "$API_BASE/${model.id}:generateContent"

/**
 * Backoff used when a 429 response body doesn't carry a server-suggested
 * "Please retry in X.Ys" hint — pessimistic enough that a TPM-bound
 * quota window can usually drain, short enough not to feel like the
 * tool froze.
 */
private const val DEFAULT_429_BACKOFF_MS: Long = 5_000L

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
 *
 * Transient-failure handling: a single retry is attempted on 429 (rate
 * limit) and on `HttpRequestTimeoutException` (Ktor client timeout).
 * For 429 the wait honours Gemini's "Please retry in X.Ys" hint when
 * present, otherwise falls back to [DEFAULT_429_BACKOFF_MS]. Persistent
 * failures (both attempts fail) return an `LlmResult.error`.
 *
 * Prints the request-header block before sending; the response footer
 * (`duration`, `tokens: …`) is the [SessionLoop]'s job — it has the running
 * totals that the per-turn footer needs to reference.
 */
class GeminiApi(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: GeminiModel = GeminiModel.Default,
) : LlmApi {
    override suspend fun send(
        messages: List<Message>,
        params: GenerationParams,
    ): LlmResult {
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

        // Single-retry loop: at most one extra attempt on 429 or timeout.
        // Anything else (5xx, network errors etc.) — fail immediately;
        // the user already has a high-level error path that keeps the
        // session alive, so they decide if/when to nudge again.
        var hasRetried = false
        while (true) {
            val httpResponse = try {
                val systemMsgs = messages.filter { it.role == Role.SYSTEM }
                val turnMsgs = messages.filter { it.role != Role.SYSTEM }
                httpClient.post(endpointFor(model)) {
                    url { parameters.append("key", apiKey) }
                    contentType(ContentType.Application.Json)
                    setBody(
                        GeminiRequest(
                            contents = turnMsgs.mapNotNull { it.toContentOrNull() },
                            generationConfig = params.toGenerationConfig(),
                            systemInstruction = buildSystemInstruction(systemMsgs, params.endSequence),
                        )
                    )
                }
            } catch (e: HttpRequestTimeoutException) {
                if (hasRetried) {
                    return LlmResult(
                        text = null,
                        error = "Request timeout (after retry): ${redactGeminiKey(e.message)}",
                    )
                }
                logWarn("[retry] timeout — retrying once")
                hasRetried = true
                continue
            } catch (e: Exception) {
                return LlmResult(
                    text = null,
                    error = "Request failed: ${redactGeminiKey(e.message)}",
                )
            }

            if (httpResponse.status == HttpStatusCode.TooManyRequests) {
                val rawBody = httpResponse.bodyAsText()
                if (hasRetried) {
                    return LlmResult(
                        text = null,
                        error = "Gemini API 429 (after retry): ${redactGeminiKey(rawBody.take(500))}",
                    )
                }
                val waitMs = parseRetryAfterMillis(rawBody) ?: DEFAULT_429_BACKOFF_MS
                logWarn("[retry] 429 — waiting ${waitMs}ms, retrying once")
                delay(waitMs)
                hasRetried = true
                continue
            }

            if (!httpResponse.status.isSuccess()) {
                val body = httpResponse.bodyAsText().take(500)
                return LlmResult(
                    text = null,
                    error = "Gemini API ${httpResponse.status}: ${redactGeminiKey(body)}",
                )
            }

            val response: GeminiResponse = httpResponse.body()
            val text = response.candidates
                .firstOrNull()?.content?.parts
                ?.joinToString(separator = "") { it.text }
                ?.takeIf { it.isNotBlank() }
            val usage = response.usageMetadata?.let { u ->
                Usage(
                    promptTokens = u.promptTokenCount ?: 0,
                    outputTokens = u.candidatesTokenCount ?: 0,
                    thoughtsTokens = u.thoughtsTokenCount ?: 0,
                    totalTokens = u.totalTokenCount ?: 0,
                )
            }
            return LlmResult(text = text, usage = usage)
        }
    }
}

/**
 * Strip the `?key=…` query-string value out of any string that might
 * contain it (timeout exception messages, HTTP error bodies). Keeps the
 * `?key=` marker so the reader knows redaction happened; only the value
 * is replaced. Anywhere this function is used, the call site has
 * already decided "this text is going to a place where a key would be
 * a leak" — keep it that way.
 */
internal fun redactGeminiKey(text: String?): String =
    text?.replace(Regex("(\\?key=)[A-Za-z0-9_-]+"), "$1***") ?: ""

/**
 * Pull a server-suggested retry delay out of a Gemini 429 body. The
 * response usually contains a sentence like "Please retry in 3.307306781s".
 * Returns the wait time in milliseconds, or `null` when no such hint is
 * present (caller falls back to a default).
 */
internal fun parseRetryAfterMillis(body: String): Long? {
    val match = Regex("retry in ([\\d.]+)s", RegexOption.IGNORE_CASE).find(body) ?: return null
    val seconds = match.groupValues[1].toDoubleOrNull() ?: return null
    return (seconds * 1000).toLong().coerceAtLeast(0L)
}

/**
 * Map a neutral [Message] into Gemini's wire [Content]. `Role.SYSTEM`
 * returns null — those messages travel via [buildSystemInstruction]
 * (Gemini has a dedicated `systemInstruction` field, separate from
 * `contents`). Caller is expected to filter SYSTEM out of `contents`
 * upstream; the null fallback here is exhaustiveness insurance.
 */
private fun Message.toContentOrNull(): Content? = when (role) {
    Role.SYSTEM -> null
    Role.USER -> Content(parts = listOf(Part(text)), role = "user")
    Role.ASSISTANT -> Content(parts = listOf(Part(text)), role = "model")
}

/**
 * Builds Gemini's `generationConfig` from the neutral [GenerationParams].
 * Only [GenerationParams.stopSequences] / [maxTokens] / [temperature] land
 * here — [GenerationParams.endSequence] is a different concept (best-effort
 * trailing marker) and is handled via [buildSystemInstruction]. Returns
 * `null` when nothing is set so the field is omitted from the request body.
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
 * Compose Gemini's `systemInstruction` from neutral inputs per the
 * [LlmApi.send] contract: all [systemMessages] are concatenated with
 * blank-line separators, and [endSequence] (if set) appends the
 * trailing-marker instruction with the same separator. Returns `null`
 * when both inputs are empty so the field is omitted from the request
 * body — same bytes as before this change for callers that pass only
 * USER/ASSISTANT and no `endSequence`.
 *
 * Bundles everything into one `Part` (Gemini accepts multi-part bodies
 * but a single concatenated part keeps the DTO untouched and the test
 * assertions trivial).
 */
internal fun buildSystemInstruction(
    systemMessages: List<Message>,
    endSequence: String?,
): SystemInstruction? {
    val parts = mutableListOf<String>()
    if (systemMessages.isNotEmpty()) {
        parts += systemMessages.joinToString("\n\n") { it.text }
    }
    if (endSequence != null) {
        parts += "Always end your response with the literal text: \"$endSequence\""
    }
    if (parts.isEmpty()) return null
    return SystemInstruction(parts = listOf(Part(parts.joinToString("\n\n"))))
}

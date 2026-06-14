package ru.den.writes.code.project01.cliJvm

/**
 * Provider-agnostic surface for talking to a chat-style LLM.
 *
 * The whole point of this interface is to keep callers (e.g. [Agent])
 * unaware of which backend they're hitting: Gemini, OpenAI, anything
 * else. All exchange types are neutral — no provider DTOs cross this
 * boundary. Each implementation maps the neutral types to its own
 * wire format internally.
 *
 * Implementations are expected to encapsulate their own transport,
 * credentials, model id and any provider-specific quirks. Callers
 * supply only the conversation and generation knobs.
 */
internal interface LlmApi {
    /**
     * Sends the full conversation as [messages] (the caller appends the
     * current user turn before calling — see the OpenAI-style `messages`
     * shape) and returns the model's reply alongside token usage.
     *
     * On failure, returns [LlmResult] with `text = null` and a populated
     * [LlmResult.error]. The contract is "non-null error means the
     * implementation already logged technical details" — callers print
     * the human-readable `error` field and move on.
     */
    suspend fun send(
        messages: List<Message>,
        params: GenerationParams,
    ): LlmResult
}

/**
 * Who said this turn. Mapped by each [LlmApi] implementation to its
 * provider-specific role string (e.g. Gemini calls the assistant `"model"`,
 * OpenAI calls it `"assistant"` — that mapping is not the agent's problem).
 */
internal enum class Role { USER, ASSISTANT }

/**
 * One turn in the running conversation.
 */
internal data class Message(
    val role: Role,
    val text: String,
)

/**
 * Generation knobs an [LlmApi] caller can tweak. All optional — null
 * means "don't set, take the provider's default". Implementations decide
 * how to express each knob (or whether to silently ignore one that
 * doesn't translate).
 *
 * [endSequence] is best-effort: there is no portable "force the response
 * to end with this exact string" API across providers; implementations
 * typically lower it to a system instruction.
 */
internal data class GenerationParams(
    val maxTokens: Int? = null,
    val stopSequences: List<String>? = null,
    val endSequence: String? = null,
    val temperature: Double? = null,
)

/**
 * Neutral token-accounting type. [thoughtsTokens] is always 0 for
 * providers that don't separately bill reasoning tokens (e.g. OpenRouter).
 */
internal data class Usage(
    val promptTokens: Int,
    val outputTokens: Int,
    val thoughtsTokens: Int = 0,
    val totalTokens: Int,
)

/**
 * What an [LlmApi.send] call returns.
 *
 * On success: [text] is the assistant's reply and [usage] reports the
 * token counts the provider charged for; [error] is null.
 *
 * On failure: [text] is null, [usage] is null (we didn't make it far
 * enough to parse usage), and [error] is a short human-readable
 * description suitable for printing to the user — e.g. an HTTP status
 * + the head of the response body. The implementation is expected to
 * have already logged any technical detail above and beyond what fits
 * in [error].
 */
internal data class LlmResult(
    val text: String?,
    val usage: Usage? = null,
    val error: String? = null,
)

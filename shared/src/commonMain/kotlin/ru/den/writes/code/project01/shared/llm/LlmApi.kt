package ru.den.writes.code.project01.shared.llm

/**
 * Provider-agnostic surface for talking to a chat-style LLM.
 *
 * The whole point of this interface is to keep callers (e.g. [SessionLoop])
 * unaware of which backend they're hitting: Gemini, OpenAI, anything
 * else. All exchange types are neutral — no provider DTOs cross this
 * boundary. Each implementation maps the neutral types to its own
 * wire format internally.
 *
 * Implementations are expected to encapsulate their own transport,
 * credentials, model id and any provider-specific quirks. Callers
 * supply only the conversation and generation knobs.
 */
interface LlmApi {
    /**
     * Sends the full conversation as [messages] (the caller appends the
     * current user turn before calling — see the OpenAI-style `messages`
     * shape) and returns the model's reply alongside token usage.
     *
     * **Role.SYSTEM routing.** Implementations MUST collect every
     * `Role.SYSTEM` entry in [messages] (in input order, anywhere in the
     * list — not necessarily contiguous at the start) and route them
     * into the provider's native system slot. The rules are:
     *
     * - Multiple SYSTEM entries are concatenated with `"\n\n"` between
     *   them into one combined block.
     * - If [GenerationParams.endSequence] is set, the
     *   "Always end your response with..." instruction is appended to
     *   that same block with another `"\n\n"` separator.
     * - If neither SYSTEM input nor `endSequence` is present, no system
     *   block is sent (the relevant request-body field is omitted — same
     *   bytes as today for callers that pass only USER/ASSISTANT).
     * - `Role.USER` and `Role.ASSISTANT` keep their position relative
     *   to one another after SYSTEM entries are filtered out.
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
 *
 * [SYSTEM] is the "instructions / persona / constraints" channel; the
 * provider lifts it into a native system slot per the [LlmApi.send]
 * contract. The history database never stores SYSTEM rows — they live
 * only in the wire list assembled per-turn (see the memory layer
 * pipeline).
 */
enum class Role { SYSTEM, USER, ASSISTANT }

/**
 * One turn in the running conversation.
 */
data class Message(
    val role: Role,
    val text: String,
    /**
     * Tool calls this turn carries — set only on a [Role.ASSISTANT] turn the
     * model produced as a tool invocation (its [text] is then empty). null for
     * ordinary turns. Ephemeral: only the function-calling loop builds these,
     * and they are never persisted to history.
     */
    val toolCalls: List<ToolCall>? = null,
    /**
     * When non-null, this turn carries a tool *result* back to the model: the
     * name of the tool whose textual output sits in [text]. Maps to the
     * provider's function-response wire shape. null for ordinary turns; also
     * ephemeral.
     */
    val toolResultFor: String? = null,
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
data class GenerationParams(
    val maxTokens: Int? = null,
    val stopSequences: List<String>? = null,
    val endSequence: String? = null,
    val temperature: Double? = null,
    /**
     * Gemini thinking budget — tokens the model may spend reasoning before the
     * visible answer. null = leave the provider default (thinking on); 0 =
     * disable thinking on Gemini 2.5 Flash so the whole [maxTokens] goes to the
     * answer instead of being eaten by reasoning. Other providers ignore it.
     */
    val thinkingBudget: Int? = null,
    /**
     * Tool / function declarations advertised to the model. null (default)
     * means no tools and a request shape identical to before; providers
     * without function-calling support ignore it.
     */
    val tools: List<ToolDefinition>? = null,
)

/**
 * Neutral token-accounting type. [thoughtsTokens] reports reasoning /
 * thinking tokens when the provider surfaces them separately — Gemini
 * via `usageMetadata.thoughtsTokenCount`, Hugging Face via
 * `completion_tokens_details.reasoning_tokens` on reasoning-capable
 * models. Always 0 for providers (OpenRouter) or responses that fold
 * reasoning into the regular completion count.
 */
data class Usage(
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
data class LlmResult(
    val text: String?,
    val usage: Usage? = null,
    val error: String? = null,
    /**
     * Tool calls the model requested instead of (or alongside) a textual
     * answer. Empty (default) for a plain reply or any provider that does not
     * surface function calls.
     */
    val toolCalls: List<ToolCall> = emptyList(),
)

package ru.den.writes.code.project01.shared.llm

/**
 * Deterministic [LlmApi] stub for tests.
 *
 * Scriptable: pre-queue [LlmResult]s via [queue] or the [queueText]
 * convenience; each `send()` consumes one. Empty queue → returns a
 * synthetic error result (mirrors the production «failed call, already
 * logged» contract; agent prints `[error]` and continues).
 *
 * Inspectable: every call is recorded into [calls] with a defensive
 * copy of the messages so later mutations to the agent's history don't
 * retroactively change what tests see.
 */
internal class FakeLlmApi : LlmApi {

    private val responses = ArrayDeque<LlmResult>()

    data class Call(val messages: List<Message>, val params: GenerationParams)

    val calls: MutableList<Call> = mutableListOf()

    override suspend fun send(
        messages: List<Message>,
        params: GenerationParams,
    ): LlmResult {
        calls += Call(messages.toList(), params)
        return if (responses.isEmpty()) {
            LlmResult(text = null, error = "FakeLlmApi: no scripted response")
        } else {
            responses.removeFirst()
        }
    }

    /** Append literal [LlmResult]s to the queue, in order. */
    fun queue(vararg results: LlmResult) {
        this.responses += results
    }

    /**
     * Convenience: queue a successful reply with default-shaped usage
     * counts. Tests that just need a non-empty reply pick this; tests
     * that care about specific token numbers pass [Usage] explicitly via
     * [queue].
     */
    fun queueText(
        text: String,
        promptTokens: Int = 10,
        outputTokens: Int = 5,
        thoughtsTokens: Int = 0,
    ) {
        responses += LlmResult(
            text = text,
            usage = Usage(
                promptTokens = promptTokens,
                outputTokens = outputTokens,
                thoughtsTokens = thoughtsTokens,
                totalTokens = promptTokens + outputTokens + thoughtsTokens,
            ),
        )
    }
}

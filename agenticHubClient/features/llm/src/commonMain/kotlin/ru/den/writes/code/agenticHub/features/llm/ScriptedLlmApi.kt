package ru.den.writes.code.agenticHub.features.llm

/**
 * Scriptable/inspectable driver behind
 * [llmTestModule][ru.den.writes.code.agenticHub.features.llm.di.llmTestModule]'s
 * fake [LlmApi]. Public so tests (any module) can configure it off the graph;
 * the [LlmApi] itself ([ScriptedLlmApi]) stays `internal`. Mirrors `FakeLlmApi`
 * from `:testing`, but as a graph-resident holder rather than a hand-constructed
 * stub.
 */
public class FakeLlmScript {
    private val responses = ArrayDeque<LlmResult>()

    /** One recorded `send`, with a defensive copy of the messages. */
    public data class Call(val messages: List<Message>, val params: GenerationParams)

    /** Every `send()` seen by the fake, in order. */
    public val calls: MutableList<Call> = mutableListOf()

    /** Append literal [LlmResult]s to the queue, consumed one per `send()`. */
    public fun queue(vararg results: LlmResult) {
        responses += results
    }

    /** Convenience: queue a successful reply with default-shaped usage counts. */
    public fun queueText(
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

    internal fun record(messages: List<Message>, params: GenerationParams) {
        calls += Call(messages.toList(), params)
    }

    internal fun next(): LlmResult =
        if (responses.isEmpty()) {
            LlmResult(text = null, error = "FakeLlmScript: no scripted response")
        } else {
            responses.removeFirst()
        }
}

/**
 * Fake [LlmApi] driven by a [FakeLlmScript]. `internal` — only reachable via
 * [llmTestModule][ru.den.writes.code.agenticHub.features.llm.di.llmTestModule],
 * under the [LlmApi] interface. Empty script → synthetic error result (matches
 * the production «failed call, already logged» contract).
 */
internal class ScriptedLlmApi(private val script: FakeLlmScript) : LlmApi {
    override suspend fun send(messages: List<Message>, params: GenerationParams): LlmResult {
        script.record(messages, params)
        return script.next()
    }
}

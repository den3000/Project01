package ru.den.writes.code.project01.cliJvm

/**
 * Deterministic [LlmApi] stub for tests.
 *
 * Scriptable: pre-queue responses via [queue]; each `send()` consumes
 * one. Empty queue → returns `null` (mirrors the production «failed
 * call, already logged» contract).
 *
 * Inspectable: every call is recorded into [calls] with a defensive
 * copy of the messages so later mutations to the agent's history don't
 * retroactively change what tests see.
 */
internal class FakeLlmApi(
    private val responses: ArrayDeque<String?> = ArrayDeque(),
) : LlmApi {

    data class Call(val messages: List<Message>, val params: GenerationParams)

    val calls: MutableList<Call> = mutableListOf()

    override suspend fun send(
        messages: List<Message>,
        params: GenerationParams,
    ): String? {
        calls += Call(messages.toList(), params)
        return if (responses.isEmpty()) null else responses.removeFirst()
    }

    /** Append the given responses to the queue, in order. */
    fun queue(vararg responses: String?) {
        this.responses += responses
    }
}

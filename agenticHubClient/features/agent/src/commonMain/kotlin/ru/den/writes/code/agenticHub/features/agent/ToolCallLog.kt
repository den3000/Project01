package ru.den.writes.code.agenticHub.features.agent

/**
 * What the runtime actually executed this session, oldest first — the judge's
 * evidence, and the one input a reply cannot fabricate.
 *
 * Session-scoped rather than per-turn because work spans stages: a fact is
 * fetched while clarifying, used while solving, and restated while wrapping up.
 * A judge holding only the current turn reads every restatement as unfounded and
 * blocks turn after turn. The question it must answer is "was this ever
 * established in this session", and only a session-wide list answers it.
 *
 * [capacity] bounds the prompt. The default fits the worst single turn whole —
 * `AgentResponder.MAX_TOOL_ROUNDS` calls per attempt, two attempts when a turn is
 * retried after a breach — so the most recent turn is never partially visible,
 * which would be worse than not seeing it at all: a half-listed turn reads as a
 * step that was never taken.
 *
 * Not derived from the history store: the tool exchange is deliberately ephemeral
 * and never persisted (see `AgentResponder`), so there is nothing there to derive
 * from. Injected into `TurnEngine` rather than owned by it so the eviction rule is
 * testable without a model.
 */
class ToolCallLog(private val capacity: Int = DEFAULT_CAPACITY) {
    private val window = ArrayDeque<ExecutedToolCall>()

    /** How many calls fell out of the window — the prompt has to say so, not hide it. */
    var dropped: Int = 0
        private set

    /** The window, oldest first. */
    val calls: List<ExecutedToolCall> get() = window.toList()

    /**
     * Append this turn's calls, evicting the oldest past [capacity].
     *
     * Recorded for every turn including a blocked one: a tool that ran has
     * already had its effect — a ticket it created is on disk — and the judge of
     * the next turn has to see that, or it will call the ticket invented.
     */
    fun record(calls: List<ExecutedToolCall>) {
        window += calls
        while (window.size > capacity) {
            window.removeFirst()
            dropped++
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 12
    }
}

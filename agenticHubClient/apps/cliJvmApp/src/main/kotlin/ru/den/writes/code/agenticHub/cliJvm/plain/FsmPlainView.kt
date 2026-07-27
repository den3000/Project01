package ru.den.writes.code.agenticHub.cliJvm.plain

import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome

/** What the task FSM did with a turn the task paid for (the `[fsm] …` line on stderr). */
internal data class FsmPlainView(val outcome: RetryOutcome) : PlainView {
    override fun stderr(): List<String> = when (outcome) {
        // A spent attempt on a task that carries on is not news. The view-model already
        // filters these out; the branch is here so no caller can produce a blank line.
        is RetryOutcome.Retried -> emptyList()
        // The number names the attempt that is starting — the same one the engine put in
        // the branch it opened.
        is RetryOutcome.Restarted ->
            listOf("[fsm] task restarted from the top — attempt ${outcome.task.taskRetryState.attempt + 1}")
        is RetryOutcome.GaveUp ->
            listOf(
                "[fsm] task gave up at ${outcome.task.stage.keyword} — " +
                    "out of attempts (${outcome.reason.name})"
            )
    }
}

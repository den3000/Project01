package ru.den.writes.code.agenticHub.features.fsm

/**
 * What [Task.retry] decided, and therefore what the caller has to do.
 *
 * A bare `Task?` could not express this. It cannot tell "spent a stage attempt,
 * carry on" from "the task was restarted", and the second demands work outside
 * the task object: branch the conversation so the failed attempt leaves the wire,
 * and rebuild the engine, which has no reset for its own FSM state. A caller
 * cannot infer that obligation from a returned data class.
 */
sealed interface RetryOutcome {

    /**
     * The task after the decision. For every variant but [GaveUp] it is what the
     * next turn runs on; for [GaveUp] it is the state the run died in.
     */
    val task: Task

    /** Same stage, one attempt lighter on the budget the reason names. Nothing else to do. */
    data class Retried(override val task: Task) : RetryOutcome

    /**
     * The task starts over from [Stage.INITIAL], one task attempt lighter.
     * Branch the history and rebuild the engine before the next turn.
     */
    data class Restarted(override val task: Task) : RetryOutcome

    /**
     * Out of attempts — stop the run. [task] is the state it died in (budgets
     * exhausted, stage wherever it stalled), kept for the report rather than to
     * be run again; [reason] is what finally broke it.
     */
    data class GaveUp(override val task: Task, val reason: RetryReason) : RetryOutcome
}

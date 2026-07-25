package ru.den.writes.code.agenticHub.features.fsm

/**
 * What [TaskStateMachine.advance] did with the stage the model proposed.
 *
 * Only [Advanced] carries a changed [task]; the rest hand back what they were
 * given, because a refused move must leave the task exactly where it was. What
 * they do carry is [reason] — the failure this outcome amounts to — so that
 * "the move did not happen" and "the budget it costs" cannot drift apart in the
 * caller.
 */
sealed interface AdvanceOutcome {

    /** The task after the decision; unchanged unless the move was applied. */
    val task: Task

    /**
     * The retry this outcome should be charged as, or null when nothing failed.
     * Feed it to [TaskStateMachine.retry] — the machine deliberately does not
     * spend it here, so that one call decides the move and one call spends the
     * budget.
     */
    val reason: RetryReason?

    /**
     * The move was legal and applied. Both inner budgets come back fresh: they
     * measure what one stage cost, and a task that keeps reaching new stages is
     * not stalling, however expensive it has been.
     */
    data class Advanced(
        override val task: Task,
        val from: Stage?,
        val to: Stage,
    ) : AdvanceOutcome {
        override val reason: RetryReason? get() = null
    }

    /**
     * The model named the stage it is already in, so nothing moved. [allowed] is
     * where the stage can actually go — the caller quotes it back, because the
     * mistake is that the marker names a destination and was used as a label.
     */
    data class Repeated(
        override val task: Task,
        val stage: Stage,
        val allowed: Set<Stage>,
    ) : AdvanceOutcome {
        override val reason: RetryReason get() = RetryReason.STAGE_REPEATED
    }

    /** The move skipped a stage (or left a terminal one); it was refused and the stage held. */
    data class Rejected(
        override val task: Task,
        val from: Stage?,
        val proposed: Stage,
        val allowed: Set<Stage>,
    ) : AdvanceOutcome {
        override val reason: RetryReason get() = RetryReason.STAGE_REJECTED
    }

    /**
     * The task is paused, so the proposal was not considered at all. Not a
     * failure: pause is a standing instruction to hold the stage, and charging a
     * retry for obeying it would restart a task that is doing what it was told.
     */
    data class Held(override val task: Task) : AdvanceOutcome {
        override val reason: RetryReason? get() = null
    }
}

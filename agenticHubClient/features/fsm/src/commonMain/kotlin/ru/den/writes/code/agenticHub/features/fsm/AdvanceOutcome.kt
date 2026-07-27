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
     * The move was legal and applied.
     *
     * [newGround] says whether it took the task past everything this attempt had
     * already reached. On new ground the stage budget comes back fresh and nothing
     * is charged — that is what progress looks like. On ground already covered
     * (a step back, or the step forward that undoes one) the budget stands and the
     * turn is charged as [RetryReason.STAGE_REVISITED]: the move was legal, but the
     * task is no further along than it was, and a loop of such moves must not be free.
     */
    data class Advanced(
        override val task: Task,
        val from: Stage,
        val to: Stage,
        val newGround: Boolean,
    ) : AdvanceOutcome {
        override val reason: RetryReason? get() = if (newGround) null else RetryReason.STAGE_REVISITED
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
        val from: Stage,
        val proposed: Stage,
        val allowed: Set<Stage>,
    ) : AdvanceOutcome {
        override val reason: RetryReason get() = RetryReason.STAGE_REJECTED
    }
}

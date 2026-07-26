package ru.den.writes.code.agenticHub.features.fsm

/**
 * Everything one turn did to a task, decided in a single call to
 * [TaskStateMachine.update]: where the task stands now, what the move was, what it
 * cost, and whether the model is told about it.
 *
 * Four fields rather than a sealed hierarchy because a turn genuinely has that many
 * independent readers, and each of them wants a different one: the view renders
 * [advance], the owner of the loop acts on [retryOutcome], the next prompt is built
 * from [retryReason], and storage only ever needs [task]. Splitting them by case
 * would make every reader match on shapes it does not care about.
 *
 * The caller applies this; the machine changes nothing itself. That is what keeps
 * the module free of storage, history and clocks.
 */
data class UpdateDecision(
    /** The task after the turn — the caller's only job is to persist it. */
    val task: Task,

    /**
     * The move, when the model proposed one: applied, repeated or refused. Null when
     * there was nothing to move on — no marker, a blocked answer, a dead provider.
     */
    val advance: AdvanceOutcome?,

    /**
     * What the failure cost, when the turn cost anything. Null on a free turn. This
     * is the verdict the loop's owner has to act on: [RetryOutcome.Restarted] means
     * the conversation has to start over, [RetryOutcome.GaveUp] that the run is done.
     */
    val retryOutcome: RetryOutcome?,

    /**
     * What to tell the model on the next turn, or null to say nothing. Set only for
     * a plain [RetryOutcome.Retried]: a restarted task must not learn it was
     * restarted, and a run that gave up has nobody left to tell. The wording is the
     * caller's business — this only names what happened.
     */
    val retryReason: RetryReason?,
)

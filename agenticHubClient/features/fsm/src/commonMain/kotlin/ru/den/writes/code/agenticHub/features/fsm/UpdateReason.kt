package ru.den.writes.code.agenticHub.features.fsm

/**
 * How a turn ended, in the only terms the FSM needs: what the caller observed, not
 * what it concluded. The conclusion — which budget pays and whether the task even
 * moves — is [TaskStateMachine.update]'s to draw.
 *
 * Written as observations rather than as a [RetryReason] on purpose. Handing the
 * machine a ready-made reason would leave the caller deciding that a blocked answer
 * costs the stage and a dead provider does not, which is a rule, and rules do not
 * belong in whoever happens to be calling. Everything a turn can end as is here, so
 * "what kinds of turn are there" keeps having one answer in one place.
 */
sealed interface UpdateReason {

    /**
     * The model answered and named a stage. Whether that is progress, a repeat, an
     * illegal skip or a loop is decided from the task, not from the marker.
     */
    data class StageProposed(val stage: Stage) : UpdateReason

    /** The model answered without naming a stage at all — the quiet stall. */
    data object NoStageProposed : UpdateReason

    /** Every rewrite breached, so the answer stands but the task does not move. */
    data object JudgeBlocked : UpdateReason

    /** The provider could not be reached; the model never saw this turn. */
    data object TransportFailed : UpdateReason
}

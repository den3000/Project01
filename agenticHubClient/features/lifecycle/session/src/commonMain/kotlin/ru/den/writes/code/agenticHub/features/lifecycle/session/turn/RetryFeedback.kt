package ru.den.writes.code.agenticHub.features.lifecycle.session.turn

import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.memory.TaskStage

/**
 * The one thing the model is told between turns, derived from the one thing that
 * happened: the FSM charged a retry.
 *
 * There is no second channel. A nudge armed off its own counter, a rejection
 * surfaced from its own field and a budget ticking somewhere else were three
 * mechanisms describing the same event — "this turn cost the task something and
 * changed nothing" — and they could disagree. Here the retry IS the trigger: what
 * to say follows from [RetryReason], how firmly from how much of the budget the
 * stage has already burned.
 *
 * Only a [ru.den.writes.code.agenticHub.features.fsm.RetryOutcome.Retried] gets a
 * line. A restarted task must not be told it was restarted (a fresh attempt that
 * knows it is a second one is not a fresh attempt), and a run that gave up has
 * nobody left to tell.
 */
internal data class RetryFeedback(
    val reason: RetryReason,
    /** The stage the model asked for, when it asked for one. */
    val proposed: TaskStage? = null,
    /**
     * Where the task may go from where the charged turn left it — quoted back to the
     * model as the markers it may name. Captured with the charge rather than looked
     * up when the line is rendered: the FSM owns its transition table, and by then
     * the only honest answer would have to come from it anyway.
     */
    val allowed: Set<TaskStage> = emptySet(),
)

/**
 * Render the feedback for [stage], or null when there is nothing useful to say.
 *
 * [spent] is the stage budget already burned; it picks the volume rather than the
 * subject. The first no-move gets the plain "your marker did nothing" line, and
 * only a stage that keeps sitting still gets the full "decide this turn" nudge —
 * the wording that was tuned on live runs, now armed by the budget instead of a
 * private streak counter, so it survives a resumed task and cannot drift from the
 * budget it is warning about.
 */
internal fun retryFeedbackMessage(
    feedback: RetryFeedback,
    stage: TaskStage,
    spent: Int,
): Message? = when (feedback.reason) {
    RetryReason.NO_MARKER ->
        if (spent >= STALL_NUDGE_AFTER) stallHintMessage(stage) else noMarkerMessage(stage, feedback.allowed)

    RetryReason.STAGE_REPEATED ->
        if (spent >= STALL_NUDGE_AFTER) stallHintMessage(stage)
        else stageRepeatMessage(StageAdvance.Repeated(stage, feedback.allowed))

    RetryReason.STAGE_REJECTED -> feedback.proposed?.let {
        stageRejectionMessage(StageAdvance.Rejected(stage, it, feedback.allowed))
    }

    RetryReason.JUDGE_BLOCKED -> judgeBlockedMessage()

    // Going back over covered ground costs a turn of the budget, but saying so would
    // argue with a decision the model is entitled to make — a plan that turned out
    // wrong is exactly what the step back is for. It pays; it is not lectured. Once
    // the budget runs low the stall nudge arrives anyway, on the next no-move.
    RetryReason.STAGE_REVISITED -> null

    // Nothing the model can act on: it never saw the failed call, and a restart
    // asked for by the user or by an exhausted budget is not its business.
    RetryReason.TRANSPORT_FAILED, RetryReason.TASK_STALLED, RetryReason.USER_RESTART -> null

    // The judge's objections are handed to the agent inside the turn, while the
    // rewrite is still possible; by the next turn they are stale.
    RetryReason.JUDGE_REWRITE -> null
}

/**
 * The turn passed and named no stage at all. Softer than [stallHintMessage] on
 * purpose: one such turn is ordinary work in progress, and treating it as a stall
 * would push the model to signal a move it has not earned.
 */
private fun noMarkerMessage(stage: TaskStage, allowed: Set<TaskStage>): Message = Message(
    role = Role.SYSTEM,
    text = "$FSM_NO_MOVE Your last reply carried no [[stage:<next>]] line, so the task is still in " +
        "${stage.keyword}. That is fine while this stage's work is unfinished — but when it is done, the " +
        "reply must end with the marker for the stage you are moving to: " +
        "${allowed.joinToString(", ") { it.keyword }}.",
)

/**
 * Every rewrite breached, so the reply was withdrawn and never reached the
 * conversation. Said out loud because otherwise the model's own answer simply
 * vanishes from the history it can see, and it has no way to tell that from a
 * turn that never happened.
 */
private fun judgeBlockedMessage(): Message = Message(
    role = Role.SYSTEM,
    text = "$FSM_NO_MOVE An independent auditor rejected your previous reply and every rewrite of it, so " +
        "that answer was withdrawn and is not part of this conversation. Answer the question again, " +
        "honouring the constraints from the memory layer above.",
)

/**
 * Turns a stage may burn before the nudge goes from "your marker did nothing" to
 * "decide this turn". Two, like the streak counter it replaces: a single no-move
 * is normal, a run of them is the degeneration loop the nudge exists to break.
 * Compared against [RetryState] rather than counted separately.
 */
private const val STALL_NUDGE_AFTER = 2

package ru.den.writes.code.agenticHub.features.memory.fsm

/**
 * Why a retry is being asked for — one entry per failure the engine can actually
 * observe, so that "how many kinds of retry are there" has a single answer with
 * a single place to change it.
 *
 * An enum while every reason is budget-accounting only. The moment one needs a
 * payload (the refused `from → to` pair, the judge's objections) this becomes a
 * sealed interface with the same constants.
 */
enum class RetryReason(val level: RetryLevel) {

    /**
     * The judge rejected the reply and the agent is rewriting it.
     * `StageAdvance` never even runs. Capped by `MAX_JUDGE_ATTEMPTS`.
     */
    JUDGE_REWRITE(RetryLevel.TURN),

    /**
     * The provider errored or returned nothing. Arguably not the model's fault
     * and arguably should cost no budget at all — the wire already retries
     * underneath. Left at TURN so it does not silently eat a stage.
     */
    TRANSPORT_FAILED(RetryLevel.TURN),

    /**
     * A passed turn with no `[[stage:]]` marker at all — the quiet stall. The
     * model learns nothing on its own here, which is why this is the one case the
     * engine has to nudge rather than merely count.
     */
    NO_MARKER(RetryLevel.STAGE),

    /**
     * The model signalled the stage it is already in: not a move, not an illegal
     * move, a correctable mistake. `StageAdvance.Repeated`.
     */
    STAGE_REPEATED(RetryLevel.STAGE),

    /**
     * The model proposed an illegal skip and the engine held the stage.
     * `StageAdvance.Rejected`.
     */
    STAGE_REJECTED(RetryLevel.STAGE),

    /**
     * Every rewrite attempt breached, so the turn is shown but not persisted and
     * the stage is held. `JudgeOutcome.Blocked` — the turn is spent, the task is
     * no further along.
     */
    JUDGE_BLOCKED(RetryLevel.STAGE),

    /**
     * The task burned its whole turn allowance without reaching [Stage.DONE].
     * The measured failure: a run locks in execution or validation and rewords
     * itself forever.
     */
    TASK_STALLED(RetryLevel.TASK),

    /**
     * The user asked for a restart. Whether an explicit restart should cost an
     * attempt at all is open — it is a decision, not a failure.
     */
    USER_RESTART(RetryLevel.TASK),
}

/**
 * Which budget a failure spends.
 *
 * [TURN] — inside one turn, invisible to persisted state, capped by the engine.
 * [STAGE] — the turn happened and was fine, but the FSM did not move.
 * [TASK] — the run as a whole is not converging; only a restart can help.
 */
enum class RetryLevel { TURN, STAGE, TASK }

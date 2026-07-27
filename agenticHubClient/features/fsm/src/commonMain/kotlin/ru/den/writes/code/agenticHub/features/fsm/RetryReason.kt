package ru.den.writes.code.agenticHub.features.fsm

/**
 * Why a retry is being asked for — one entry per failure a turn can actually end
 * in, so that "how many kinds of retry are there" has a single answer with a
 * single place to change it.
 *
 * Every constant here is reachable from some [UpdateReason]; nothing is kept as a
 * placeholder for a failure the engine cannot yet report. A reason no turn can
 * produce reads like a promise the module does not keep, and the tests that cover
 * it cover nothing.
 *
 * An enum while every reason is budget-accounting only. The moment one needs a
 * payload (the refused `from → to` pair, the judge's objections) this becomes a
 * sealed interface with the same constants.
 */
enum class RetryReason(val level: RetryLevel) {

    /**
     * The provider errored or returned nothing. The only reason here that is not
     * the model's doing, and the only one a restart cannot help: the wire already
     * retries underneath, so by the time it reaches the task the provider is
     * properly down, and starting the task over just points the same dead
     * endpoint at the same task from the top.
     */
    TRANSPORT_FAILED(RetryLevel.TRANSPORT),

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
     * The move was legal and applied, but it led onto ground this attempt had already
     * covered — a step back, or the step forward that undoes one.
     *
     * Charged because otherwise it is free, and free is what a loop feeds on: a run
     * measured live oscillated `execution ↔ validation` for twenty-five turns, every
     * move legal, nothing ever spent, no escalation possible. Rework is allowed and
     * costs one turn of the stage's budget, the same as any other turn that left the
     * task no further along.
     */
    STAGE_REVISITED(RetryLevel.STAGE),

    /**
     * Every rewrite attempt breached, so the turn is shown but not persisted and
     * the stage is held. `JudgeOutcome.Blocked` — the turn is spent, the task is
     * no further along.
     */
    JUDGE_BLOCKED(RetryLevel.STAGE),

}

/**
 * Which budget a failure spends.
 *
 * [STAGE] — the turn was spent and the task is no further along: the stage did
 * not move, or the answer was withdrawn. Running out does not end anything; the
 * failure is promoted and the task restarts, which is why the task budget has no
 * level of its own — nothing is charged to it directly, it is only ever spent by
 * an exhausted stage.
 * [TRANSPORT] — the model could not be reached at all. Its budget spans the whole
 * task and never refills, and running out ends the run outright: nothing the FSM
 * can do reaches a provider that is down.
 */
enum class RetryLevel { STAGE, TRANSPORT }

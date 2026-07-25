package ru.den.writes.code.agenticHub.features.memory.fsm

/**
 * A task and everything the FSM needs to keep running it: where it is
 * ([stage]), what it is for ([goal] / [notes]), and how much failure it may
 * still absorb ([stageRetryState] / [taskRetryState]).
 *
 * The retry budgets live here rather than in the engine because they must
 * survive a process restart: a task resumed tomorrow has already spent what it
 * spent today, and an engine that forgets that gets a fresh five attempts every
 * time the CLI is relaunched.
 */
data class Task(
    val taskId: String,
    val stage: Stage? = null,
    val paused: Boolean = false,
    val goal: String? = null,
    val notes: List<String> = emptyList(),
    val taskRetryState: RetryState = RetryState.task(),
    val stageRetryState: RetryState = RetryState.stage(),
) {

    /**
     * Spend one retry for [reason] and say what the caller should do next.
     *
     * Two budgets, one cascade. A stage that will not move costs a
     * [stageRetryState] attempt; when that budget runs out the failure is
     * promoted, not dropped — the whole task restarts, costing a [taskRetryState]
     * attempt and buying a fresh stage budget. Only the outer budget running out
     * ends the task ([RetryOutcome.GaveUp]).
     *
     * [RetryLevel.TURN] reasons change nothing: a rewrite inside one turn is the
     * engine's business and never reaches persisted state. They are in the
     * taxonomy so that the list of ways a turn can fail lives in one place.
     */
    fun retry(reason: RetryReason): RetryOutcome = when (reason.level) {
        RetryLevel.TURN -> RetryOutcome.Untouched(this)
        RetryLevel.STAGE -> retryStage(reason)
        RetryLevel.TASK -> restart(reason)
    }

    /** Stay where we are, one stage attempt lighter; out of them → escalate to a restart. */
    private fun retryStage(reason: RetryReason): RetryOutcome {
        val spent = stageRetryState.spend() ?: return restart(reason)
        return RetryOutcome.Retried(copy(stageRetryState = spent))
    }

    /**
     * Start the task over: back to [Stage.INITIAL], notes dropped, stage budget
     * fresh, one task attempt lighter. [RetryOutcome.GaveUp] when the attempts
     * are gone.
     *
     * Persisted state only. A restart has to forget three things and this covers
     * exactly one of them — the conversation (history branch) and the engine's
     * ephemeral FSM state (stall streak, pending feedback) are outside this
     * object, which is why the restart is announced rather than merely returned.
     */
    private fun restart(reason: RetryReason): RetryOutcome {
        val spent = taskRetryState.spend() ?: return RetryOutcome.GaveUp(this, reason)
        return RetryOutcome.Restarted(
            copy(
                stage = Stage.INITIAL,
                notes = emptyList(),
                paused = false,
                taskRetryState = spent,
                stageRetryState = RetryState.stage(),
            ),
        )
    }

    /**
     * A stage genuinely moved, so the stage budget starts over — the budget is
     * "turns spent stuck in THIS stage", not "turns spent on the task". Without
     * this a task that legitimately took four turns per stage would restart on
     * the third stage having never actually stalled.
     */
    fun advancedTo(next: Stage): Task = copy(stage = next, stageRetryState = RetryState.stage())
}

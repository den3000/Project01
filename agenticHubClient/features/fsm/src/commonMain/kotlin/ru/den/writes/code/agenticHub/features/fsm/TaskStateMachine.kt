package ru.den.writes.code.agenticHub.features.fsm

/**
 * Every decision the task FSM makes: where a task may go next, what to do with a
 * move the model proposed, and what a failure costs. [Task], [Stage] and
 * [RetryState] carry state; nothing there decides anything.
 *
 * Pure (no I/O, no clock, no randomness) so it ports to every target and reads
 * as a table of rules rather than a flow. The caller applies the returned task —
 * persisting it, branching history, rebuilding the engine — the machine only says
 * what should happen.
 */
object TaskStateMachine {

    /**
     * Stages reachable from [stage] in one step: forward, plus a single step back
     * to revisit the prior phase. [Stage.DONE] is terminal (empty set) — a
     * finished task is not reopened automatically.
     */
    fun allowedNext(stage: Stage): Set<Stage> = when (stage) {
        Stage.CLARIFICATION -> setOf(Stage.PLANNING)
        Stage.PLANNING -> setOf(Stage.EXECUTION, Stage.CLARIFICATION)
        Stage.EXECUTION -> setOf(Stage.VALIDATION, Stage.PLANNING)
        Stage.VALIDATION -> setOf(Stage.DONE, Stage.EXECUTION)
        Stage.DONE -> emptySet()
    }

    /**
     * Whether moving [from] → [to] is permitted. A null [from] (a task with no
     * stage yet) accepts any [to] as initialization: there is no prior stage to
     * violate. Once a stage is set, only [allowedNext] entries pass.
     */
    fun canTransition(from: Stage?, to: Stage): Boolean =
        if (from == null) true else to in allowedNext(from)

    /**
     * Apply the move [proposed] for [task] and say what happened.
     *
     * The transition is validated here rather than described in a prompt: a model
     * will happily skip a stage if asked, so the hard "no planning → done"
     * guarantee has to live in code. The model only ever proposes; this decides.
     *
     * Only [AdvanceOutcome.Advanced] changes the task — and it refills both inner
     * budgets, because reaching a new stage is the proof that the task is moving
     * (see [AdvanceOutcome.Advanced]). Every other outcome hands the task back
     * untouched and names, in [AdvanceOutcome.reason], the failure the caller
     * should then spend through [retry].
     */
    fun advance(task: Task, proposed: Stage): AdvanceOutcome {
        // Pause is orthogonal to the stage and outranks it: while it is on, the
        // task holds where it is and a proposal is not a failure to be charged for.
        if (task.paused) return AdvanceOutcome.Held(task)
        val from = task.stage
        // Not a move, but not an illegal one either: the model used the marker to
        // label where it already is. Its own outcome, so the caller can say so
        // instead of swallowing it — silence here is the FSM's main lock.
        if (proposed == from) return AdvanceOutcome.Repeated(task, proposed, allowedNext(proposed))
        if (!canTransition(from, proposed)) {
            return AdvanceOutcome.Rejected(task, from, proposed, from?.let(::allowedNext).orEmpty())
        }
        return AdvanceOutcome.Advanced(
            task = task.copy(
                stage = proposed,
                stageRetryState = RetryState.stage(),
                turnRetryState = RetryState.turn(),
            ),
            from = from,
            to = proposed,
        )
    }

    /**
     * Spend one retry for [reason] and say what the caller should do next.
     *
     * A cascade of three nested budgets, plus one that sits outside it. A failure
     * inside a turn costs a [Task.turnRetryState] attempt, a stage that will not
     * move costs a [Task.stageRetryState] one. Either inner budget running out is
     * not the end — the failure is promoted, and the whole task restarts on a
     * [Task.taskRetryState] attempt with both inner budgets fresh. The outermost
     * budget running out ends the run ([RetryOutcome.GaveUp]).
     *
     * [RetryLevel.TRANSPORT] is not part of that cascade: it spends
     * [Task.transportRetryState] and, when that is gone, gives up on the spot.
     * Every other failure is something the task might do differently on a second
     * pass; an unreachable provider is not, so promoting it would only spend the
     * restarts on a wall.
     */
    fun retry(task: Task, reason: RetryReason): RetryOutcome = when (reason.level) {
        RetryLevel.TURN -> retryTurn(task, reason)
        RetryLevel.STAGE -> retryStage(task, reason)
        RetryLevel.TASK -> restart(task, reason)
        RetryLevel.TRANSPORT -> retryTransport(task, reason)
    }

    /** Try the turn again, one turn attempt lighter; out of them → escalate to a restart. */
    private fun retryTurn(task: Task, reason: RetryReason): RetryOutcome {
        val spent = task.turnRetryState.spend() ?: return restart(task, reason)
        return RetryOutcome.Retried(task.copy(turnRetryState = spent))
    }

    /** Stay where we are, one stage attempt lighter; out of them → escalate to a restart. */
    private fun retryStage(task: Task, reason: RetryReason): RetryOutcome {
        val spent = task.stageRetryState.spend() ?: return restart(task, reason)
        return RetryOutcome.Retried(task.copy(stageRetryState = spent))
    }

    /**
     * Wait out the outage, one transport attempt lighter; out of them → the run is
     * over. No escalation: see [retry].
     */
    private fun retryTransport(task: Task, reason: RetryReason): RetryOutcome {
        val spent = task.transportRetryState.spend() ?: return RetryOutcome.GaveUp(task, reason)
        return RetryOutcome.Retried(task.copy(transportRetryState = spent))
    }

    /**
     * Start the task over: back to [Stage.INITIAL], both inner budgets fresh, one
     * task attempt lighter. [RetryOutcome.GaveUp] when the attempts are gone.
     *
     * [Task.goal] and [Task.notes] survive. The restart forgets how the attempt
     * went, not what it was for, and both of those are what the user said about
     * the task — not residue the failed attempt left behind. Dropping the notes
     * would start the second attempt less informed than the first, exactly when
     * the task is already struggling. (If the model is ever allowed to write
     * notes of its own, that content IS residue and the field has to be split.)
     *
     * The inner budgets reset because the restart is a new attempt at the whole
     * task, not a continuation of the failed one — carrying their spent state over
     * would let a restarted task escalate again on its first stall, and the five
     * restarts would burn down in five turns. [Task.transportRetryState] is
     * pointedly not among them: it counts an outage, which a new attempt does
     * nothing about, so it carries over untouched.
     *
     * Persisted state only. A restart has to forget three things and this covers
     * exactly one of them — the conversation (history branch) and the engine's
     * ephemeral FSM state (stall streak, pending feedback) are outside the task,
     * which is why the restart is announced rather than merely returned.
     */
    private fun restart(task: Task, reason: RetryReason): RetryOutcome {
        val spent = task.taskRetryState.spend() ?: return RetryOutcome.GaveUp(task, reason)
        return RetryOutcome.Restarted(
            task.copy(
                stage = Stage.INITIAL,
                paused = false,
                taskRetryState = spent,
                stageRetryState = RetryState.stage(),
                turnRetryState = RetryState.turn(),
            ),
        )
    }
}

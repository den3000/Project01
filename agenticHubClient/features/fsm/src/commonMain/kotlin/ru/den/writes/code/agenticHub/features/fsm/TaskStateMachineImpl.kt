package ru.den.writes.code.agenticHub.features.fsm

/**
 * The rules themselves: every decision the task FSM makes, behind the one method
 * [TaskStateMachine] exposes. [Task], [Stage] and [RetryState] carry state; nothing
 * there decides anything.
 *
 * Pure (no I/O, no clock, no randomness) so it ports to every target and reads as a
 * table of rules rather than a flow. The caller applies the returned task —
 * persisting it, branching history — the machine only says what should happen.
 *
 * Holds no state: two instances behave identically, and one per graph is plenty.
 */
public class TaskStateMachineImpl : TaskStateMachine {

    /**
     * Joining the two halves of a turn: [advance] answers "may it move" and [retry]
     * "what does a failure cost", and the join is where the rules live — that a legal
     * move can still be charged, that a blocked answer costs the stage while a dead
     * provider does not, that a restarted task is not told it was restarted. Kept
     * here rather than at the call site, where a second caller would invent it again.
     */
    override fun update(task: Task, reason: UpdateReason): UpdateDecision = when (reason) {
        is UpdateReason.StageProposed -> {
            val advance = advance(task, reason.stage)
            when (val price = advance.reason) {
                // A move onto new ground: the stage budget already came back fresh
                // inside `advance`, and the turn owes nothing.
                null -> decision(advance.task, advance = advance)
                // Applied but no further along (a step back, or the step forward that
                // undoes one), repeated, or refused — the move is charged to the task
                // as `advance` left it, so the stage it now sits on is the one paying.
                else -> charge(advance.task, price).copy(advance = advance)
            }
        }

        UpdateReason.NoStageProposed -> charge(task, RetryReason.NO_MARKER)
        UpdateReason.JudgeBlocked -> charge(task, RetryReason.JUDGE_BLOCKED)
        UpdateReason.TransportFailed -> charge(task, RetryReason.TRANSPORT_FAILED)
    }

    /**
     * Stages reachable from [stage] in one step: forward, plus a single step back
     * to revisit the prior phase. [Stage.DONE] is terminal (empty set) — a
     * finished task is not reopened automatically.
     *
     * Internal: the table leaves the module inside [UpdateDecision.allowedNext],
     * already read off the task the turn ended on. Handing it out as a function
     * would let a caller ask at the wrong moment and quote stages the task cannot
     * reach any more.
     */
    internal fun allowedNext(stage: Stage): Set<Stage> = when (stage) {
        Stage.CLARIFICATION -> setOf(Stage.PLANNING)
        Stage.PLANNING -> setOf(Stage.EXECUTION, Stage.CLARIFICATION)
        Stage.EXECUTION -> setOf(Stage.VALIDATION, Stage.PLANNING)
        Stage.VALIDATION -> setOf(Stage.DONE, Stage.EXECUTION)
        Stage.DONE -> emptySet()
    }

    /**
     * Whether moving [from] → [to] is permitted: exactly the [allowedNext]
     * entries, no special cases.
     *
     * There is deliberately no "stage unknown" case. A task is always somewhere
     * in the machine — a stored file without a recorded stage is a parsing
     * problem, and whoever loads it substitutes [Stage.INITIAL] at the boundary.
     * Letting an unknown stage through here would mean a task could reach
     * [Stage.DONE] in one move by having no stage set, which is precisely the
     * guarantee this table exists to give.
     */
    internal fun canTransition(from: Stage, to: Stage): Boolean = to in allowedNext(from)

    /**
     * Apply the move [proposed] for [task] and say what happened.
     *
     * The transition is validated here rather than described in a prompt: a model
     * will happily skip a stage if asked, so the hard "no planning → done"
     * guarantee has to live in code. The model only ever proposes; this decides.
     *
     * Only [AdvanceOutcome.Advanced] changes the task — and it refills the stage
     * budget only when the move reaches **new ground**, i.e. a stage deeper than
     * anything this attempt has already seen ([Task.deepestStage]). Every other
     * outcome hands the task back untouched and names, in [AdvanceOutcome.reason],
     * the failure the caller should then spend through [retry].
     *
     * Depth rather than movement, because movement is not progress. Measured live:
     * a run oscillated `execution ↔ validation` for twenty-five turns and was never
     * charged a thing — a step back is legal, the step forward after it is legal
     * too, and each of them refreshed the budget. Refreshing on depth leaves an
     * honest re-plan cheap (going back costs nothing extra) while a loop pays for
     * every turn of it and eventually restarts.
     */
    internal fun advance(task: Task, proposed: Stage): AdvanceOutcome {
        val from = task.stage
        // Not a move, but not an illegal one either: the model used the marker to
        // label where it already is. Its own outcome, so the caller can say so
        // instead of swallowing it — silence here is the FSM's main lock.
        if (proposed == from) return AdvanceOutcome.Repeated(task, proposed, allowedNext(proposed))
        if (!canTransition(from, proposed)) {
            return AdvanceOutcome.Rejected(task, from, proposed, allowedNext(from))
        }
        // maxOf, not the field alone: a task built or loaded without a recorded depth
        // has obviously reached the stage it is standing on, and reading it that way
        // keeps every construction of [Task] sane instead of only the careful ones.
        val reached = maxOf(task.deepestStage, from)
        val newGround = proposed.ordinal > reached.ordinal
        return AdvanceOutcome.Advanced(
            task = task.copy(
                stage = proposed,
                deepestStage = maxOf(reached, proposed),
                stageRetryState = if (newGround) RetryState.stage() else task.stageRetryState,
            ),
            from = from,
            to = proposed,
            newGround = newGround,
        )
    }

    /**
     * Spend one retry for [reason] and say what the caller should do next.
     *
     * Two nested budgets, plus one that sits outside them. A turn the stage paid
     * for and got nothing back — no move, or an answer sent back to be rewritten —
     * costs a [Task.stageRetryState] attempt. That budget running out is not the
     * end: the failure is promoted, and the whole task restarts on a
     * [Task.taskRetryState] attempt with a fresh stage budget. The outer budget
     * running out ends the run ([RetryOutcome.GaveUp]).
     *
     * [RetryLevel.TRANSPORT] is not part of that cascade: it spends
     * [Task.transportRetryState] and, when that is gone, gives up on the spot.
     * Every other failure is something the task might do differently on a second
     * pass; an unreachable provider is not, so promoting it would only spend the
     * restarts on a wall.
     */
    internal fun retry(task: Task, reason: RetryReason): RetryOutcome = when (reason.level) {
        RetryLevel.STAGE -> retryStage(task, reason)
        RetryLevel.TASK -> restart(task, reason)
        RetryLevel.TRANSPORT -> retryTransport(task, reason)
    }

    /**
     * Spend [reason] and wrap the verdict for the caller. The model hears about it
     * only when the task simply tries again: a restart is meant to be invisible to
     * it, and a run that gave up has nobody left to tell.
     */
    private fun charge(task: Task, reason: RetryReason): UpdateDecision {
        val outcome = retry(task, reason)
        return decision(
            task = outcome.task,
            retryOutcome = outcome,
            retryReason = reason.takeIf { outcome is RetryOutcome.Retried },
        )
    }

    /** A decision about [task], with the onward stages read off where it ended up. */
    private fun decision(
        task: Task,
        advance: AdvanceOutcome? = null,
        retryOutcome: RetryOutcome? = null,
        retryReason: RetryReason? = null,
    ) = UpdateDecision(
        task = task,
        advance = advance,
        retryOutcome = retryOutcome,
        retryReason = retryReason,
        allowedNext = allowedNext(task.stage),
    )

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
     * Start the task over: back to [Stage.INITIAL], stage budget fresh, one task
     * attempt lighter. [RetryOutcome.GaveUp] when the attempts are gone.
     *
     * [Task.goal] and [Task.notes] survive. The restart forgets how the attempt
     * went, not what it was for, and both of those are what the user said about
     * the task — not residue the failed attempt left behind. Dropping the notes
     * would start the second attempt less informed than the first, exactly when
     * the task is already struggling. (If the model is ever allowed to write
     * notes of its own, that content IS residue and the field has to be split.)
     *
     * The stage budget resets because the restart is a new attempt at the whole
     * task, not a continuation of the failed one — carrying its spent state over
     * would let a restarted task escalate again on its first stall, and the five
     * restarts would burn down in five turns. [Task.transportRetryState] is
     * pointedly not reset: it counts an outage, which a new attempt does nothing
     * about, so it carries over untouched.
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
                deepestStage = Stage.INITIAL,
                taskRetryState = spent,
                stageRetryState = RetryState.stage(),
            ),
        )
    }
}

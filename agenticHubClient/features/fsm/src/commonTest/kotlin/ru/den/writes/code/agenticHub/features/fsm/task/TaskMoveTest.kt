package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.AdvanceOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.Task
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachineImpl
import ru.den.writes.code.agenticHub.features.fsm.UpdateDecision
import ru.den.writes.code.agenticHub.features.fsm.UpdateReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The transition table as behaviour: propose a move, see whether it was applied or refused, and
 * what it cost. Forward is progress and comes free; everything else is legal at most, and the
 * stage pays for it.
 *
 * Tasks enter each move with part of the stage budget already burned, so "the budget came back
 * fresh" says something about the move rather than about the fixture.
 */
class TaskMoveTest {

    private val machine = TaskStateMachineImpl()

    @Test
    fun `when the move follows the forward path - then it is applied and costs nothing`() {
        // given
        val spent = RetryState(attempt = 3, max = RetryState.STAGE_MAX)
        val forward = listOf(
            task(stage = Stage.CLARIFICATION, stageRetryState = spent) to Stage.PLANNING,
            task(stage = Stage.PLANNING, stageRetryState = spent) to Stage.EXECUTION,
            task(stage = Stage.EXECUTION, stageRetryState = spent) to Stage.VALIDATION,
            task(stage = Stage.VALIDATION, stageRetryState = spent) to Stage.DONE,
        )

        // when
        val actuals = forward.fold(emptyList<Triple<Task, Stage, UpdateDecision>>()) { moves, (task, to) ->
            moves + Triple(task, to, machine.update(task, UpdateReason.StageProposed(to)))
        }

        // then
        actuals.forEach { (task, to, actual) ->
            val move = "${task.stage} -> $to"
            val advance = assertIs<AdvanceOutcome.Advanced>(actual.advance, "outcome($move)")
            assertTrue(advance.newGround, "newGround($move)")
            assertEquals(task.stage, advance.from, "from($move)")
            assertEquals(to, advance.to, "to($move)")
            assertEquals(to, actual.task.stage, "stage($move)")
            assertEquals(to, actual.task.deepestStage, "depth($move)")
            assertNull(actual.retryReason, "price($move)")
            assertEquals(RetryState.stage(), actual.task.stageRetryState, "budget($move)")
        }
    }

    @Test
    fun `when the move steps back one stage - then it is applied and charged`() {
        // given
        val spent = RetryState(attempt = 3, max = RetryState.STAGE_MAX)
        val back = listOf(
            task(stage = Stage.PLANNING, stageRetryState = spent) to Stage.CLARIFICATION,
            task(stage = Stage.EXECUTION, stageRetryState = spent) to Stage.PLANNING,
            task(stage = Stage.VALIDATION, stageRetryState = spent) to Stage.EXECUTION,
        )

        // when
        val actuals = back.fold(emptyList<Triple<Task, Stage, UpdateDecision>>()) { moves, (task, to) ->
            moves + Triple(task, to, machine.update(task, UpdateReason.StageProposed(to)))
        }

        // then
        actuals.forEach { (task, to, actual) ->
            val move = "${task.stage} -> $to"
            val advance = assertIs<AdvanceOutcome.Advanced>(actual.advance, "outcome($move)")
            assertFalse(advance.newGround, "newGround($move)")
            assertEquals(to, actual.task.stage, "stage($move)")
            assertEquals(task.stage, actual.task.deepestStage, "depth($move)")
            assertEquals(RetryReason.STAGE_REVISITED, actual.retryReason, "price($move)")
            assertEquals(spent.attempt + 1, actual.task.stageRetryState.attempt, "budget($move)")
        }
    }

    @Test
    fun `when the move skips a stage forward - then it is refused and charged`() {
        // given
        val spent = RetryState(attempt = 3, max = RetryState.STAGE_MAX)
        val illegal = listOf(
            task(stage = Stage.CLARIFICATION, stageRetryState = spent) to Stage.EXECUTION,
            task(stage = Stage.CLARIFICATION, stageRetryState = spent) to Stage.DONE,
            task(stage = Stage.PLANNING, stageRetryState = spent) to Stage.VALIDATION,
            task(stage = Stage.PLANNING, stageRetryState = spent) to Stage.DONE,
            task(stage = Stage.EXECUTION, stageRetryState = spent) to Stage.DONE,
        )

        // when
        val actuals = illegal.fold(emptyList<Triple<Task, Stage, UpdateDecision>>()) { moves, (task, to) ->
            moves + Triple(task, to, machine.update(task, UpdateReason.StageProposed(to)))
        }

        // then
        actuals.forEach { (task, to, actual) ->
            val move = "${task.stage} -> $to"
            val advance = assertIs<AdvanceOutcome.Rejected>(actual.advance, "outcome($move)")
            assertEquals(task.stage, advance.from, "from($move)")
            assertEquals(to, advance.proposed, "proposed($move)")
            assertEquals(task.stage, actual.task.stage, "stage($move)")
            assertEquals(RetryReason.STAGE_REJECTED, actual.retryReason, "price($move)")
            assertEquals(spent.attempt + 1, actual.task.stageRetryState.attempt, "budget($move)")
        }
    }

    @Test
    fun `when the move steps back over a stage - then it is refused and charged`() {
        // given
        val spent = RetryState(attempt = 3, max = RetryState.STAGE_MAX)
        val illegal = listOf(
            task(stage = Stage.EXECUTION, stageRetryState = spent) to Stage.CLARIFICATION,
            task(stage = Stage.VALIDATION, stageRetryState = spent) to Stage.PLANNING,
            task(stage = Stage.VALIDATION, stageRetryState = spent) to Stage.CLARIFICATION,
        )

        // when
        val actuals = illegal.fold(emptyList<Triple<Task, Stage, UpdateDecision>>()) { moves, (task, to) ->
            moves + Triple(task, to, machine.update(task, UpdateReason.StageProposed(to)))
        }

        // then
        actuals.forEach { (task, to, actual) ->
            val move = "${task.stage} -> $to"
            val advance = assertIs<AdvanceOutcome.Rejected>(actual.advance, "outcome($move)")
            assertEquals(task.stage, advance.from, "from($move)")
            assertEquals(to, advance.proposed, "proposed($move)")
            assertEquals(task.stage, actual.task.stage, "stage($move)")
            assertEquals(RetryReason.STAGE_REJECTED, actual.retryReason, "price($move)")
            assertEquals(spent.attempt + 1, actual.task.stageRetryState.attempt, "budget($move)")
        }
    }

    @Test
    fun `when a stage names itself - then nothing moves and the turn is charged`() {
        // given
        val spent = RetryState(attempt = 3, max = RetryState.STAGE_MAX)
        val tasks = Stage.entries.map { stage -> task(stage = stage, stageRetryState = spent) }

        // when
        val actuals = tasks.fold(emptyList<Pair<Task, UpdateDecision>>()) { moves, task ->
            moves + (task to machine.update(task, UpdateReason.StageProposed(task.stage)))
        }

        // then
        actuals.forEach { (task, actual) ->
            val stage = task.stage
            val advance = assertIs<AdvanceOutcome.Repeated>(actual.advance, "outcome($stage)")
            assertEquals(stage, advance.stage, "stage($stage)")
            assertEquals(stage, actual.task.stage, "held($stage)")
            assertEquals(RetryReason.STAGE_REPEATED, actual.retryReason, "price($stage)")
            assertEquals(spent.attempt + 1, actual.task.stageRetryState.attempt, "budget($stage)")
        }
    }

    @Test
    fun `when a done task is moved anywhere - then every target is refused and charged`() {
        // given
        val spent = RetryState(attempt = 3, max = RetryState.STAGE_MAX)
        val task = task(stage = Stage.DONE, stageRetryState = spent)
        val targets = Stage.entries.filter { it != Stage.DONE }

        // when
        val actuals = targets.fold(emptyList<Pair<Stage, UpdateDecision>>()) { moves, to ->
            moves + (to to machine.update(task, UpdateReason.StageProposed(to)))
        }

        // then
        actuals.forEach { (to, actual) ->
            val move = "done -> $to"
            val advance = assertIs<AdvanceOutcome.Rejected>(actual.advance, "outcome($move)")
            assertEquals(Stage.DONE, advance.from, "from($move)")
            assertEquals(to, advance.proposed, "proposed($move)")
            assertEquals(emptySet(), advance.allowed, "allowed($move)")
            assertEquals(Stage.DONE, actual.task.stage, "stage($move)")
            assertEquals(RetryReason.STAGE_REJECTED, actual.retryReason, "price($move)")
            assertEquals(spent.attempt + 1, actual.task.stageRetryState.attempt, "budget($move)")
        }
    }
}

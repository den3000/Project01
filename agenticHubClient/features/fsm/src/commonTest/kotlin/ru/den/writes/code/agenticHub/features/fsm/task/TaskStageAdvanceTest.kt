package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.AdvanceOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.Task
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskStageAdvanceTest {

    private val machine = TaskStateMachine()

    @Test
    fun `when a legal move is proposed - then the stage advances`() {
        // given
        val stage = Stage.PLANNING
        val proposed = Stage.EXECUTION
        val task = task(stage = stage)

        // when
        val actual = machine.advance(task, proposed)

        // then
        assertIs<AdvanceOutcome.Advanced>(actual)
        assertEquals(stage, actual.from)
        assertEquals(proposed, actual.to)
        assertEquals(proposed, actual.task.stage)
        assertNull(actual.reason)
    }

    @Test
    fun `when the stage advances - then only the stage budget starts over`() {
        // given
        val task = task(
            stage = Stage.PLANNING,
            notes = listOf("scope agreed"),
            taskRetryState = RetryState(attempt = 2, max = RetryState.TASK_MAX),
            stageRetryState = RetryState(attempt = 4, max = RetryState.STAGE_MAX),
            transportRetryState = RetryState(attempt = 3, max = RetryState.TRANSPORT_MAX),
        )
        val proposed = Stage.EXECUTION

        // when
        val actual = machine.advance(task, proposed)

        // then
        val expected = task.copy(
            stage = proposed,
            deepestStage = proposed,
            stageRetryState = RetryState.stage(),
        )
        assertIs<AdvanceOutcome.Advanced>(actual)
        assertEquals(expected, actual.task)
    }

    @Test
    fun `when a task is created without a stage - then it starts at clarification`() {
        // given
        val task = Task(taskId = TASK_ID)

        // when
        val actual = task.stage

        // then
        assertEquals(Stage.CLARIFICATION, actual)
    }

    @Test
    fun `when a fresh task is moved to done - then the jump is refused like any other`() {
        // given
        val task = Task(taskId = TASK_ID)
        val proposed = Stage.DONE

        // when
        val actual = machine.advance(task, proposed)

        // then
        assertIs<AdvanceOutcome.Rejected>(actual)
        assertEquals(Stage.CLARIFICATION, actual.from)
        assertEquals(proposed, actual.proposed)
        assertEquals(setOf(Stage.PLANNING), actual.allowed)
        assertEquals(task, actual.task)
    }

    @Test
    fun `when a fresh task is moved to planning - then it advances`() {
        // given
        val task = Task(taskId = TASK_ID)
        val proposed = Stage.PLANNING

        // when
        val actual = machine.advance(task, proposed)

        // then
        assertIs<AdvanceOutcome.Advanced>(actual)
        assertEquals(Stage.CLARIFICATION, actual.from)
        assertEquals(proposed, actual.task.stage)
    }

    @Test
    fun `when a move revisits ground already reached - then it is charged and the budget is not refreshed`() {
        // given
        val spent = RetryState(attempt = 6, max = RetryState.STAGE_MAX)
        val task = task(stage = Stage.VALIDATION, deepestStage = Stage.VALIDATION, stageRetryState = spent)

        // when
        val actual = machine.advance(task, Stage.EXECUTION)

        // then
        assertIs<AdvanceOutcome.Advanced>(actual)
        assertFalse(actual.newGround)
        assertEquals(RetryReason.STAGE_REVISITED, actual.reason)
        assertEquals(spent, actual.task.stageRetryState)
        assertEquals(Stage.VALIDATION, actual.task.deepestStage)
    }

    @Test
    fun `when a move goes forward onto ground already reached - then it is charged like a step back`() {
        // given
        val spent = RetryState(attempt = 6, max = RetryState.STAGE_MAX)
        val task = task(stage = Stage.EXECUTION, deepestStage = Stage.VALIDATION, stageRetryState = spent)

        // when — forward on the table, but the task has stood on validation before
        val actual = machine.advance(task, Stage.VALIDATION)

        // then
        assertIs<AdvanceOutcome.Advanced>(actual)
        assertFalse(actual.newGround)
        assertEquals(RetryReason.STAGE_REVISITED, actual.reason)
        assertEquals(spent, actual.task.stageRetryState)
        assertEquals(Stage.VALIDATION, actual.task.deepestStage)
    }

    @Test
    fun `when a move goes onto new ground - then it costs nothing and the stage budget starts over`() {
        // given
        val spent = RetryState(attempt = 4, max = RetryState.STAGE_MAX)
        val task = task(stage = Stage.PLANNING, deepestStage = Stage.PLANNING, stageRetryState = spent)

        // when
        val actual = machine.advance(task, Stage.EXECUTION)

        // then
        assertIs<AdvanceOutcome.Advanced>(actual)
        assertTrue(actual.newGround)
        assertNull(actual.reason)
        assertEquals(RetryState.stage(), actual.task.stageRetryState)
    }

    @Test
    fun `when the depth was never recorded - then the stage the task stands on counts as reached`() {
        // given
        // A task built or loaded without a depth: standing at validation means validation
        // has been reached, whatever the field happens to hold.
        val spent = RetryState(attempt = 6, max = RetryState.STAGE_MAX)
        val task = Task(taskId = TASK_ID, stage = Stage.VALIDATION, stageRetryState = spent)

        // when
        val actual = machine.advance(task, Stage.EXECUTION)

        // then
        assertIs<AdvanceOutcome.Advanced>(actual)
        assertEquals(spent, actual.task.stageRetryState)
    }

    @Test
    fun `when the current stage is proposed again - then nothing moves`() {
        // given
        val stage = Stage.EXECUTION
        val task = task(stage = stage)

        // when
        val actual = machine.advance(task, stage)

        // then
        assertIs<AdvanceOutcome.Repeated>(actual)
        assertEquals(stage, actual.stage)
        assertEquals(setOf(Stage.VALIDATION, Stage.PLANNING), actual.allowed)
        assertEquals(task, actual.task)
        assertEquals(RetryReason.STAGE_REPEATED, actual.reason)
    }

    @Test
    fun `when a skipping move is proposed - then it is refused and the stage held`() {
        // given
        val stage = Stage.PLANNING
        val proposed = Stage.DONE
        val task = task(stage = stage)

        // when
        val actual = machine.advance(task, proposed)

        // then
        assertIs<AdvanceOutcome.Rejected>(actual)
        assertEquals(stage, actual.from)
        assertEquals(proposed, actual.proposed)
        assertEquals(setOf(Stage.EXECUTION, Stage.CLARIFICATION), actual.allowed)
        assertEquals(task, actual.task)
        assertEquals(RetryReason.STAGE_REJECTED, actual.reason)
    }
}

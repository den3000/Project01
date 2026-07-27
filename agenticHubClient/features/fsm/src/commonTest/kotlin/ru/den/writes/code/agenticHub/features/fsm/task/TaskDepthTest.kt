package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.AdvanceOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.Task
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachineImpl
import ru.den.writes.code.agenticHub.features.fsm.UpdateReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the stage budget is measured against: how deep the task has been, not how much it has
 * moved. Which moves the table lets through and what they cost is `TaskMoveTest`; here
 * the task stands somewhere shallower than it has already reached, which the table cannot say
 * anything about.
 */
class TaskDepthTest {

    private val machine = TaskStateMachineImpl()

    @Test
    fun `when a move goes forward onto ground already reached - then it is charged like a step back`() {
        // given
        val spent = RetryState(attempt = 6, max = RetryState.STAGE_MAX)
        val task = task(stage = Stage.EXECUTION, deepestStage = Stage.VALIDATION, stageRetryState = spent)

        // when
        val actual = machine.update(task, UpdateReason.StageProposed(Stage.VALIDATION))

        // then
        val advance = assertIs<AdvanceOutcome.Advanced>(actual.advance)
        assertFalse(advance.newGround)
        assertEquals(Stage.EXECUTION, advance.from)
        assertEquals(Stage.VALIDATION, advance.to)
        assertEquals(Stage.VALIDATION, actual.task.stage)
        assertEquals(Stage.VALIDATION, actual.task.deepestStage)
        assertIs<RetryOutcome.Retried>(actual.retryOutcome)
        assertEquals(RetryReason.STAGE_REVISITED, actual.retryReason)
        assertEquals(spent.attempt + 1, actual.task.stageRetryState.attempt)
        assertEquals(0, actual.task.taskRetryState.attempt)
        assertEquals(0, actual.task.transportRetryState.attempt)
        assertEquals(setOf(Stage.DONE, Stage.EXECUTION), actual.allowedNext)
    }

    @Test
    fun `when the depth was never recorded - then the stage the task stands on counts as reached`() {
        // given
        val spent = RetryState(attempt = 6, max = RetryState.STAGE_MAX)
        val task = Task(taskId = TASK_ID, stage = Stage.VALIDATION, stageRetryState = spent)

        // when
        val actual = machine.update(task, UpdateReason.StageProposed(Stage.EXECUTION))

        // then
        val advance = assertIs<AdvanceOutcome.Advanced>(actual.advance)
        assertFalse(advance.newGround)
        assertEquals(Stage.VALIDATION, advance.from)
        assertEquals(Stage.EXECUTION, advance.to)
        assertEquals(Stage.EXECUTION, actual.task.stage)
        assertEquals(Stage.VALIDATION, actual.task.deepestStage)
        assertIs<RetryOutcome.Retried>(actual.retryOutcome)
        assertEquals(RetryReason.STAGE_REVISITED, actual.retryReason)
        assertEquals(spent.attempt + 1, actual.task.stageRetryState.attempt)
        assertEquals(0, actual.task.taskRetryState.attempt)
        assertEquals(0, actual.task.transportRetryState.attempt)
        assertEquals(setOf(Stage.VALIDATION, Stage.PLANNING), actual.allowedNext)
    }

    @Test
    fun `when a move reaches new depth - then only the stage budget starts over`() {
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
        val actual = machine.update(task, UpdateReason.StageProposed(proposed))

        // then
        val expected = task.copy(
            stage = proposed,
            deepestStage = proposed,
            stageRetryState = RetryState.stage(),
        )
        val advance = assertIs<AdvanceOutcome.Advanced>(actual.advance)
        assertTrue(advance.newGround)
        assertEquals(Stage.PLANNING, advance.from)
        assertEquals(proposed, advance.to)
        assertNull(actual.retryOutcome)
        assertNull(actual.retryReason)
        assertEquals(expected, actual.task)
        assertEquals(setOf(Stage.VALIDATION, Stage.PLANNING), actual.allowedNext)
    }
}

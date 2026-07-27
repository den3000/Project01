package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachineImpl
import ru.den.writes.code.agenticHub.features.fsm.UpdateReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * One turn decided end to end: what the caller observed goes in, what the task became comes out.
 *
 * This file is about the mapping alone — that each [UpdateReason] reaches the right budget and
 * comes back as a plain retry. Which moves the table allows and what each costs is
 * `TaskMoveTest`, how depth decides the price is `TaskDepthTest`, and what happens when a
 * budget runs out is `TaskRetryTest`, where it runs out by being spent. A stage proposal appears
 * here once, to show that it reaches the move at all.
 */
class TaskUpdateTest {

    private val machine = TaskStateMachineImpl()

    @Test
    fun `when no stage was proposed - then there is no move and the stage pays`() {
        // given
        val task = task(stage = Stage.EXECUTION)

        // when
        val actual = machine.update(task, UpdateReason.NoStageProposed)

        // then
        assertNull(actual.advance)
        assertEquals(Stage.EXECUTION, actual.task.stage)
        assertIs<RetryOutcome.Retried>(actual.retryOutcome)
        assertEquals(RetryReason.NO_MARKER, actual.retryReason)
        assertEquals(1, actual.task.stageRetryState.attempt)
        assertEquals(0, actual.task.taskRetryState.attempt)
        assertEquals(0, actual.task.transportRetryState.attempt)
        assertEquals(setOf(Stage.VALIDATION, Stage.PLANNING), actual.allowedNext)
    }

    @Test
    fun `when the judge blocked the answer - then the stage pays like any turn that did not move`() {
        // given
        val task = task(stage = Stage.EXECUTION)

        // when
        val actual = machine.update(task, UpdateReason.JudgeBlocked)

        // then
        assertNull(actual.advance)
        assertEquals(Stage.EXECUTION, actual.task.stage)
        assertIs<RetryOutcome.Retried>(actual.retryOutcome)
        assertEquals(RetryReason.JUDGE_BLOCKED, actual.retryReason)
        assertEquals(1, actual.task.stageRetryState.attempt)
        assertEquals(0, actual.task.taskRetryState.attempt)
        assertEquals(0, actual.task.transportRetryState.attempt)
    }

    @Test
    fun `when the provider was unreachable - then the transport budget pays instead`() {
        // given
        val task = task(stage = Stage.EXECUTION)

        // when
        val actual = machine.update(task, UpdateReason.TransportFailed)

        // then
        assertNull(actual.advance)
        assertEquals(Stage.EXECUTION, actual.task.stage)
        assertIs<RetryOutcome.Retried>(actual.retryOutcome)
        assertEquals(RetryReason.TRANSPORT_FAILED, actual.retryReason)
        assertEquals(1, actual.task.transportRetryState.attempt)
        assertEquals(0, actual.task.stageRetryState.attempt)
        assertEquals(0, actual.task.taskRetryState.attempt)
    }
}

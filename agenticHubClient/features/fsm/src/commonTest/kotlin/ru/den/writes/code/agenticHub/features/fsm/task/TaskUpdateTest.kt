package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.AdvanceOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachineImpl
import ru.den.writes.code.agenticHub.features.fsm.UpdateReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * One turn decided end to end: what the caller observed goes in, what the task became
 * comes out. `advance` and `retry` each answer half of this; the halves are joined here.
 */
class TaskUpdateTest {

    private val machine = TaskStateMachineImpl()

    @Test
    fun `when the proposed stage reaches new ground - then the turn costs nothing`() {
        // given
        val task = task(stage = Stage.PLANNING, deepestStage = Stage.PLANNING)

        // when
        val actual = machine.update(task, UpdateReason.StageProposed(Stage.EXECUTION))

        // then
        assertIs<AdvanceOutcome.Advanced>(actual.advance)
        assertEquals(Stage.EXECUTION, actual.task.stage)
        assertNull(actual.retryOutcome)
        assertNull(actual.retryReason)
        assertEquals(setOf(Stage.VALIDATION, Stage.PLANNING), actual.allowedNext)
    }

    @Test
    fun `when the proposed stage revisits covered ground - then the move applies and is charged`() {
        // given
        val task = task(stage = Stage.EXECUTION, deepestStage = Stage.VALIDATION)

        // when
        val actual = machine.update(task, UpdateReason.StageProposed(Stage.VALIDATION))

        // then
        assertIs<AdvanceOutcome.Advanced>(actual.advance)
        assertEquals(Stage.VALIDATION, actual.task.stage)
        assertIs<RetryOutcome.Retried>(actual.retryOutcome)
        assertEquals(RetryReason.STAGE_REVISITED, actual.retryReason)
        assertEquals(1, actual.task.stageRetryState.attempt)
    }

    @Test
    fun `when the proposed stage is the current one - then nothing moves and the turn is charged`() {
        // given
        val task = task(stage = Stage.EXECUTION)

        // when
        val actual = machine.update(task, UpdateReason.StageProposed(Stage.EXECUTION))

        // then
        assertIs<AdvanceOutcome.Repeated>(actual.advance)
        assertEquals(Stage.EXECUTION, actual.task.stage)
        assertEquals(RetryReason.STAGE_REPEATED, actual.retryReason)
        assertEquals(1, actual.task.stageRetryState.attempt)
    }

    @Test
    fun `when the proposed stage skips one - then the move is refused and the turn is charged`() {
        // given
        val task = task(stage = Stage.PLANNING)

        // when
        val actual = machine.update(task, UpdateReason.StageProposed(Stage.DONE))

        // then
        assertIs<AdvanceOutcome.Rejected>(actual.advance)
        assertEquals(Stage.PLANNING, actual.task.stage)
        assertEquals(RetryReason.STAGE_REJECTED, actual.retryReason)
        assertEquals(1, actual.task.stageRetryState.attempt)
    }

    @Test
    fun `when no stage was proposed - then there is no move and the stage pays`() {
        // given
        val task = task(stage = Stage.EXECUTION)

        // when
        val actual = machine.update(task, UpdateReason.NoStageProposed)

        // then
        assertNull(actual.advance)
        assertEquals(RetryReason.NO_MARKER, actual.retryReason)
        assertEquals(1, actual.task.stageRetryState.attempt)
    }

    @Test
    fun `when the judge blocked the answer - then the stage pays like any turn that did not move`() {
        // given
        val task = task(stage = Stage.EXECUTION)

        // when
        val actual = machine.update(task, UpdateReason.JudgeBlocked)

        // then
        assertNull(actual.advance)
        assertEquals(RetryReason.JUDGE_BLOCKED, actual.retryReason)
        assertEquals(1, actual.task.stageRetryState.attempt)
    }

    @Test
    fun `when the provider was unreachable - then the transport budget pays instead`() {
        // given
        val task = task(stage = Stage.EXECUTION)

        // when
        val actual = machine.update(task, UpdateReason.TransportFailed)

        // then
        assertNull(actual.advance)
        assertEquals(RetryReason.TRANSPORT_FAILED, actual.retryReason)
        assertEquals(1, actual.task.transportRetryState.attempt)
        assertEquals(0, actual.task.stageRetryState.attempt)
    }

    @Test
    fun `when the charge exhausts the stage budget - then the task restarts and hears nothing`() {
        // given
        val spent = RetryState(attempt = RetryState.STAGE_MAX, max = RetryState.STAGE_MAX)
        val task = task(stage = Stage.VALIDATION, stageRetryState = spent)

        // when
        val actual = machine.update(task, UpdateReason.NoStageProposed)

        // then
        assertIs<RetryOutcome.Restarted>(actual.retryOutcome)
        assertEquals(Stage.INITIAL, actual.task.stage)
        assertEquals(1, actual.task.taskRetryState.attempt)
        assertNull(actual.retryReason)
        // Read off where the task ended up, not where it was: a restart moves it back
        // to the beginning, and quoting validation's onward stages would be a lie.
        assertEquals(setOf(Stage.PLANNING), actual.allowedNext)
    }

    @Test
    fun `when the restarts are gone too - then the run gives up and hears nothing`() {
        // given
        val task = task(
            stage = Stage.VALIDATION,
            stageRetryState = RetryState(attempt = RetryState.STAGE_MAX, max = RetryState.STAGE_MAX),
            taskRetryState = RetryState(attempt = RetryState.TASK_MAX, max = RetryState.TASK_MAX),
        )

        // when
        val actual = machine.update(task, UpdateReason.NoStageProposed)

        // then
        assertIs<RetryOutcome.GaveUp>(actual.retryOutcome)
        assertEquals(RetryReason.NO_MARKER, actual.retryOutcome.reason)
        assertNull(actual.retryReason)
    }
}

package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.AdvanceOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TaskStageAdvanceTest {

    @Test
    fun `when a legal move is proposed - then the stage advances`() {
        // given
        val stage = Stage.PLANNING
        val proposed = Stage.EXECUTION
        val task = task(stage = stage)

        // when
        val actual = TaskStateMachine.advance(task, proposed)

        // then
        assertIs<AdvanceOutcome.Advanced>(actual)
        assertEquals(stage, actual.from)
        assertEquals(proposed, actual.to)
        assertEquals(proposed, actual.task.stage)
        assertNull(actual.reason)
    }

    @Test
    fun `when the stage advances - then both inner budgets start over`() {
        // given
        val transportRetryStateAttempt = 3
        val task = task(
            stage = Stage.PLANNING,
            stageRetryState = RetryState(attempt = 4, max = RetryState.STAGE_MAX),
            turnRetryState = RetryState(attempt = 12, max = RetryState.TURN_MAX),
            transportRetryState = RetryState(
                attempt = transportRetryStateAttempt,
                max = RetryState.TRANSPORT_MAX
            ),
        )

        // when
        val actual = TaskStateMachine.advance(task, Stage.EXECUTION)

        // then
        assertIs<AdvanceOutcome.Advanced>(actual)
        assertEquals(RetryState.stage(), actual.task.stageRetryState)
        assertEquals(RetryState.turn(), actual.task.turnRetryState)
        assertEquals(transportRetryStateAttempt, actual.task.transportRetryState.attempt)
    }

    @Test
    fun `when the stage advances - then the restarts already spent still stand`() {
        // given
        val taskRetryStateAttempt = 2
        val task = task(
            stage = Stage.PLANNING,
            taskRetryState = RetryState(attempt = taskRetryStateAttempt, max = RetryState.TASK_MAX),
        )

        // when
        val actual = TaskStateMachine.advance(task, Stage.EXECUTION)

        // then
        assertIs<AdvanceOutcome.Advanced>(actual)
        assertEquals(taskRetryStateAttempt, actual.task.taskRetryState.attempt)
    }

    @Test
    fun `when a fresh task is moved - then it advances off clarification like any other`() {
        // given
        val task = task(stage = Stage.INITIAL)

        // when - then
        Stage.entries.forEach { to ->
            val actual = TaskStateMachine.advance(task, to)
            assertEquals(to == Stage.PLANNING, actual is AdvanceOutcome.Advanced, "advance(initial -> $to)")
        }
    }

    @Test
    fun `when the current stage is proposed again - then nothing moves`() {
        // given
        val stage = Stage.EXECUTION
        val task = task(stage = stage)

        // when
        val actual = TaskStateMachine.advance(task, stage)

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
        val actual = TaskStateMachine.advance(task, proposed)

        // then
        assertIs<AdvanceOutcome.Rejected>(actual)
        assertEquals(stage, actual.from)
        assertEquals(proposed, actual.proposed)
        assertEquals(setOf(Stage.EXECUTION, Stage.CLARIFICATION), actual.allowed)
        assertEquals(task, actual.task)
        assertEquals(RetryReason.STAGE_REJECTED, actual.reason)
    }

    @Test
    fun `when a paused task is moved - then the proposal is held and costs nothing`() {
        // given
        val task = task(stage = Stage.EXECUTION, paused = true)

        // when
        val actual = TaskStateMachine.advance(task, Stage.VALIDATION)

        // then
        assertIs<AdvanceOutcome.Held>(actual)
        assertEquals(task, actual.task)
        assertNull(actual.reason)
    }
}

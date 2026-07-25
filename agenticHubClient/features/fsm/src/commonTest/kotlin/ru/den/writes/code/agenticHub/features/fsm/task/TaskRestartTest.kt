package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.RetryLevel
import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TaskRestartTest {

    @Test
    fun `when a restart lands - then both inner budgets are fresh`() {
        // given
        // Otherwise the restarted task would begin already out of retries and
        // bounce straight into the next restart, burning all five in five turns.
        val task = task(
            turnRetryState = spentToTheLast(RetryState.turn()),
            stageRetryState = spentToTheLast(RetryState.stage()),
        )

        // when
        val actual = TaskStateMachine.retry(task, RetryReason.JUDGE_BLOCKED)

        // then
        assertIs<RetryOutcome.Restarted>(actual)
        assertEquals(RetryState.stage(), actual.task.stageRetryState)
        assertEquals(RetryState.turn(), actual.task.turnRetryState)
    }

    @Test
    fun `when a task-level reason is retried - then the task starts over from clarification`() {
        // given
        val task = task(stage = Stage.VALIDATION, paused = true)
        val reasons = RetryReason.entries.filter { it.level == RetryLevel.TASK }

        // when - then
        reasons.forEach { reason ->
            val actual = TaskStateMachine.retry(task, reason)
            assertIs<RetryOutcome.Restarted>(actual, "outcome($reason)")
            assertEquals(Stage.INITIAL, actual.task.stage, "stage($reason)")
            assertFalse(actual.task.paused, "paused($reason)")
            assertEquals(1, actual.task.taskRetryState.attempt, "task attempt($reason)")
        }
    }

    @Test
    fun `when a task restarts - then what the user said about it survives`() {
        // given
        // The restart forgets how the attempt went, not what the attempt was for.
        // Notes are written by the user (`/task note`) and injected every turn —
        // dropping them would start the second attempt less informed than the first.
        val task = task(stage = Stage.EXECUTION, notes = listOf("api rate-limits at 1 rps"))

        // when
        val actual = TaskStateMachine.retry(task, RetryReason.TASK_STALLED)

        // then
        assertIs<RetryOutcome.Restarted>(actual)
        assertEquals(TASK_ID, actual.task.taskId)
        assertEquals(GOAL, actual.task.goal)
        assertEquals(listOf("api rate-limits at 1 rps"), actual.task.notes)
    }

    @Test
    fun `when a task is restarted to its budget - then the next failure gives up`() {
        // given
        var task = task()

        // when - then
        repeat(RetryState.TASK_MAX) { attempt ->
            val actual = TaskStateMachine.retry(task, RetryReason.TASK_STALLED)
            assertIs<RetryOutcome.Restarted>(actual, "restart #${attempt + 1}")
            task = actual.task
        }
        assertIs<RetryOutcome.GaveUp>(TaskStateMachine.retry(task, RetryReason.TASK_STALLED))
    }

    @Test
    fun `when the restarts are exhausted - then giving up names the reason that was asked for`() {
        // given
        // A stage reason that escalated all the way through a spent task budget
        // still reports itself: the run died fighting a stage that would not move,
        // and reporting the escalation instead would hide that.
        val task = task(
            taskRetryState = spentToTheLast(RetryState.task()),
            stageRetryState = spentToTheLast(RetryState.stage()),
        )

        // when
        val actual = TaskStateMachine.retry(task, RetryReason.NO_MARKER)

        // then
        assertIs<RetryOutcome.GaveUp>(actual)
        assertEquals(RetryReason.NO_MARKER, actual.reason)
    }

    @Test
    fun `when the run gives up - then it carries the state the task died in`() {
        // given
        // For the report, not for another turn: the stage it stalled on and the
        // spent budgets are the whole diagnosis.
        val task = task(stage = Stage.VALIDATION, taskRetryState = spentToTheLast(RetryState.task()))

        // when
        val actual = TaskStateMachine.retry(task, RetryReason.USER_RESTART)

        // then
        assertIs<RetryOutcome.GaveUp>(actual)
        assertEquals(Stage.VALIDATION, actual.task.stage)
        assertTrue(actual.task.taskRetryState.exhausted)
    }
}

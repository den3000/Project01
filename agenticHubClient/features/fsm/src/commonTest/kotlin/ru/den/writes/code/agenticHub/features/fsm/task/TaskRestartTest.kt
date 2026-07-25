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
        val notes = listOf("api rate-limits at 1 rps")
        val task = task(stage = Stage.EXECUTION, notes = notes)

        // when
        val actual = TaskStateMachine.retry(task, RetryReason.TASK_STALLED)

        // then
        assertIs<RetryOutcome.Restarted>(actual)
        assertEquals(TASK_ID, actual.task.taskId)
        assertEquals(GOAL, actual.task.goal)
        assertEquals(notes, actual.task.notes)
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
        val reason = RetryReason.NO_MARKER
        val task = task(
            taskRetryState = spentToTheLast(RetryState.task()),
            stageRetryState = spentToTheLast(RetryState.stage()),
        )

        // when
        val actual = TaskStateMachine.retry(task, reason)

        // then
        assertIs<RetryOutcome.GaveUp>(actual)
        assertEquals(reason, actual.reason)
    }

    @Test
    fun `when the run gives up - then it carries the state the task died in`() {
        // given
        val stage = Stage.VALIDATION
        val task = task(stage = stage, taskRetryState = spentToTheLast(RetryState.task()))

        // when
        val actual = TaskStateMachine.retry(task, RetryReason.USER_RESTART)

        // then
        assertIs<RetryOutcome.GaveUp>(actual)
        assertEquals(stage, actual.task.stage)
        assertTrue(actual.task.taskRetryState.exhausted)
    }
}

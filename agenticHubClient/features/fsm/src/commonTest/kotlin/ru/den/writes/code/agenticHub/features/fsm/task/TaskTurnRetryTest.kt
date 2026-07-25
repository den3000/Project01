package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.RetryLevel
import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TaskTurnRetryTest {

    @Test
    fun `when a turn-level reason is retried - then only the turn budget is spent`() {
        // given
        val task = task()
        val reasons = RetryReason.entries.filter { it.level == RetryLevel.TURN }

        // when - then
        reasons.forEach { reason ->
            val actual = TaskStateMachine.retry(task, reason)
            assertIs<RetryOutcome.Retried>(actual, "outcome($reason)")
            assertEquals(1, actual.task.turnRetryState.attempt, "turn attempt($reason)")
            assertEquals(0, actual.task.stageRetryState.attempt, "stage attempt($reason)")
            assertEquals(0, actual.task.taskRetryState.attempt, "task attempt($reason)")
        }
    }

    @Test
    fun `when a turn retry lands - then the stage is left alone`() {
        // given
        val stage = Stage.PLANNING
        val notes = listOf("scope agreed")
        val task = task(stage = stage, notes = notes)

        // when
        val actual = TaskStateMachine.retry(task, RetryReason.JUDGE_REWRITE)

        // then
        assertIs<RetryOutcome.Retried>(actual)
        assertEquals(stage, actual.task.stage)
        assertEquals(notes, actual.task.notes)
    }

    @Test
    fun `when the turn budget runs out - then the task restarts instead`() {
        // given
        val task = task(turnRetryState = spentToTheLast(RetryState.turn()))

        // when
        val actual = TaskStateMachine.retry(task, RetryReason.JUDGE_REWRITE)

        // then
        assertIs<RetryOutcome.Restarted>(actual)
        assertEquals(1, actual.task.taskRetryState.attempt)
    }

    @Test
    fun `when a turn is retried to its budget - then the next retry restarts the task`() {
        // given
        var task = task()

        // when - then
        repeat(RetryState.TURN_MAX) { rewrite ->
            val actual = TaskStateMachine.retry(task, RetryReason.JUDGE_REWRITE)
            assertIs<RetryOutcome.Retried>(actual, "retry #${rewrite + 1}")
            task = actual.task
        }
        assertIs<RetryOutcome.Restarted>(TaskStateMachine.retry(task, RetryReason.JUDGE_REWRITE))
    }
}

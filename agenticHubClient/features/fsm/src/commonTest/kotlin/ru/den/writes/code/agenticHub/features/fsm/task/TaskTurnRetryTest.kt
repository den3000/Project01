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
        val task = task(stage = Stage.PLANNING, notes = listOf("scope agreed"))
        val reasons = RetryReason.entries.filter { it.level == RetryLevel.TURN }
        require(reasons.isNotEmpty()) { "no TURN-level reasons to cover" }
        val expected = task.copy(turnRetryState = RetryState(attempt = 1, max = RetryState.TURN_MAX))

        // when
        val actuals = reasons.map { reason -> reason to TaskStateMachine.retry(task, reason) }

        // then
        actuals.forEach { (reason, actual) ->
            assertIs<RetryOutcome.Retried>(actual, "outcome($reason)")
            assertEquals(expected, actual.task, "task($reason)")
        }
    }

    @Test
    fun `when a turn retry lands - then the stage is left alone`() {
        // given
        val stage = Stage.PLANNING
        val notes = listOf("scope agreed")
        val task = task(stage = stage, notes = notes)
        val reasons = RetryReason.entries.filter { it.level == RetryLevel.TURN }
        require(reasons.isNotEmpty()) { "no TURN-level reasons to cover" }

        // when
        val actuals = reasons.map { reason -> reason to TaskStateMachine.retry(task, reason) }

        // then
        actuals.forEach { (reason, actual) ->
            assertIs<RetryOutcome.Retried>(actual, "outcome($reason)")
            assertEquals(stage, actual.task.stage, "stage($reason)")
            assertEquals(notes, actual.task.notes, "notes($reason)")
        }
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
        assertEquals(RetryState.turn(), actual.task.turnRetryState)
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

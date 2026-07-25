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

class TaskStageRetryTest {

    @Test
    fun `when a stage-level reason is retried - then only the stage budget is spent`() {
        // given
        val task = task()
        val reasons = RetryReason.entries.filter { it.level == RetryLevel.STAGE }

        // when - then
        reasons.forEach { reason ->
            val actual = TaskStateMachine.retry(task, reason)
            assertIs<RetryOutcome.Retried>(actual, "outcome($reason)")
            assertEquals(1, actual.task.stageRetryState.attempt, "stage attempt($reason)")
            assertEquals(0, actual.task.taskRetryState.attempt, "task attempt($reason)")
        }
    }

    @Test
    fun `when a stage retry lands - then the stage and its notes are kept`() {
        // given
        val stage = Stage.VALIDATION
        val notes = listOf("api is rate-limited")
        val task = task(stage = stage, notes = notes)

        // when
        val actual = TaskStateMachine.retry(task, RetryReason.NO_MARKER)

        // then
        assertIs<RetryOutcome.Retried>(actual)
        assertEquals(stage, actual.task.stage)
        assertEquals(notes, actual.task.notes)
    }

    @Test
    fun `when the stage budget runs out - then the task restarts instead`() {
        // given
        val task = task(stageRetryState = spentToTheLast(RetryState.stage()))

        // when
        val actual = TaskStateMachine.retry(task, RetryReason.STAGE_REPEATED)

        // then
        assertIs<RetryOutcome.Restarted>(actual)
        assertEquals(1, actual.task.taskRetryState.attempt)
    }

    @Test
    fun `when a stalling stage is retried to its budget - then the next retry restarts the task`() {
        // given
        var task = task()

        // when - then
        repeat(RetryState.STAGE_MAX) { turn ->
            val actual = TaskStateMachine.retry(task, RetryReason.NO_MARKER)
            assertIs<RetryOutcome.Retried>(actual, "retry #${turn + 1}")
            task = actual.task
        }
        assertIs<RetryOutcome.Restarted>(TaskStateMachine.retry(task, RetryReason.NO_MARKER))
    }
}

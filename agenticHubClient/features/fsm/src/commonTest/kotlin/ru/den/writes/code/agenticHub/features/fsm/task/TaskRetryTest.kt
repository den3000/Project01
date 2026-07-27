package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachineImpl
import ru.den.writes.code.agenticHub.features.fsm.UpdateDecision
import ru.den.writes.code.agenticHub.features.fsm.UpdateReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Exhaustion: how many turns each budget actually absorbs, and what the cascade does when one
 * runs out. Always reached by spending — a task handed in with a full counter proves the
 * assertion rather than the arithmetic, and that reading is `TaskUpdateTest`'s.
 *
 * What one turn costs is not here at all: that is per-`UpdateReason`, and it is answered once,
 * next door.
 */
class TaskRetryTest {

    private val machine = TaskStateMachineImpl()

    @Test
    fun `when a stage is stalled to its budget - then the next stall restarts the task`() {
        // given
        val task = task(stage = Stage.VALIDATION, notes = listOf("scope agreed"))

        // when
        val actuals = (1..RetryState.STAGE_MAX + 1)
            .fold(emptyList<UpdateDecision>()) { decisions, _ ->
                decisions + machine.update(decisions.lastOrNull()?.task ?: task, UpdateReason.NoStageProposed)
            }

        // then
        actuals.dropLast(1).forEachIndexed { index, actual ->
            assertIs<RetryOutcome.Retried>(actual.retryOutcome, "stall #${index + 1}")
            assertEquals(Stage.VALIDATION, actual.task.stage, "held(stall #${index + 1})")
            assertEquals(index + 1, actual.task.stageRetryState.attempt, "stage(stall #${index + 1})")
        }
        actuals.last().let { actual ->
            assertIs<RetryOutcome.Restarted>(actual.retryOutcome)
            assertEquals(
                task.copy(
                    stage = Stage.CLARIFICATION,
                    deepestStage = Stage.CLARIFICATION,
                    taskRetryState = RetryState(attempt = 1, max = RetryState.TASK_MAX),
                    stageRetryState = RetryState.stage(),
                ),
                actual.task,
            )
            assertEquals(setOf(Stage.PLANNING), actual.allowedNext)
            assertNull(actual.retryReason)
        }
    }

    @Test
    fun `when every restart is spent too - then the run gives up on the failure that started it`() {
        // given
        val task = task(stage = Stage.VALIDATION, notes = listOf("scope agreed"))

        // when
        val turns = (RetryState.STAGE_MAX + 1) * (RetryState.TASK_MAX + 1)
        val actuals = (1..turns)
            .fold(emptyList<UpdateDecision>()) { decisions, _ ->
                decisions + machine.update(decisions.lastOrNull()?.task ?: task, UpdateReason.NoStageProposed)
            }

        // then
        assertEquals(RetryState.TASK_MAX, actuals.count { it.retryOutcome is RetryOutcome.Restarted })
        val gaveUp = assertIs<RetryOutcome.GaveUp>(actuals.last().retryOutcome)
        assertEquals(RetryReason.NO_MARKER, gaveUp.reason)
        assertEquals(RetryState.TASK_MAX, gaveUp.task.taskRetryState.attempt)
        assertEquals(RetryState.STAGE_MAX, gaveUp.task.stageRetryState.attempt)
        assertEquals(Stage.CLARIFICATION, gaveUp.task.stage)
        assertNull(actuals.last().retryReason)
    }

    @Test
    fun `when transport failures reach their budget - then the run gives up without restarting`() {
        // given
        val task = task(stage = Stage.PLANNING, notes = listOf("scope agreed"))

        // when
        val actuals = (1..RetryState.TRANSPORT_MAX + 1)
            .fold(emptyList<UpdateDecision>()) { decisions, _ ->
                decisions + machine.update(decisions.lastOrNull()?.task ?: task, UpdateReason.TransportFailed)
            }

        // then
        actuals.dropLast(1).forEachIndexed { index, actual ->
            assertIs<RetryOutcome.Retried>(actual.retryOutcome, "failure #${index + 1}")
            assertEquals(index + 1, actual.task.transportRetryState.attempt, "transport(failure #${index + 1})")
            assertEquals(0, actual.task.stageRetryState.attempt, "stage(failure #${index + 1})")
        }
        actuals.last().let { actual ->
            val gaveUp = assertIs<RetryOutcome.GaveUp>(actual.retryOutcome)
            assertEquals(RetryReason.TRANSPORT_FAILED, gaveUp.reason)
            assertEquals(Stage.PLANNING, actual.task.stage)
            assertEquals(RetryState.TRANSPORT_MAX, actual.task.transportRetryState.attempt)
            assertEquals(0, actual.task.stageRetryState.attempt)
            assertEquals(0, actual.task.taskRetryState.attempt)
            assertNull(actual.retryReason)
        }
    }
}

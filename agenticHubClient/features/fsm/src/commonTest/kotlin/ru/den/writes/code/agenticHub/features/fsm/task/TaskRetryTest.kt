package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.Task
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachineImpl
import ru.den.writes.code.agenticHub.features.fsm.UpdateDecision
import ru.den.writes.code.agenticHub.features.fsm.UpdateReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The three budgets and the cascade between them, driven by repeating a turn that gets nowhere.
 *
 * Exhaustion is always reached by actually spending it — a task handed in with a full counter
 * would prove the assertion rather than the arithmetic.
 */
class TaskRetryTest {

    private val machine = TaskStateMachineImpl()

    @Test
    fun `when a turn leaves the task where it was - then only the stage budget is spent`() {
        // given — every way a turn can end without taking the task any further
        val stalls = listOf(
            UpdateReason.NoStageProposed,
            UpdateReason.JudgeBlocked,
            UpdateReason.StageProposed(Stage.EXECUTION),
            UpdateReason.StageProposed(Stage.DONE),
        )
        val task = task(stage = Stage.EXECUTION, notes = listOf("scope agreed"))

        // when
        val actuals = stalls.map { reason -> reason to machine.update(task, reason) }

        // then
        actuals.forEach { (reason, actual) ->
            assertIs<RetryOutcome.Retried>(actual.retryOutcome, "outcome($reason)")
            assertEquals(1, actual.task.stageRetryState.attempt, "stage($reason)")
            assertEquals(0, actual.task.taskRetryState.attempt, "task($reason)")
            assertEquals(0, actual.task.transportRetryState.attempt, "transport($reason)")
        }
    }

    @Test
    fun `when a stage is stalled to its budget - then the next stall restarts the task`() {
        // given
        val task = task(stage = Stage.VALIDATION, notes = listOf("scope agreed"))

        // when — one turn per stage attempt, plus the one that finds the budget gone
        val actuals = stall(task, times = RetryState.STAGE_MAX + 1)

        // then
        actuals.dropLast(1).forEachIndexed { index, actual ->
            assertIs<RetryOutcome.Retried>(actual.retryOutcome, "stall #${index + 1}")
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
        }
    }

    @Test
    fun `when every restart is spent too - then the run gives up on the failure that started it`() {
        // given
        val task = task(stage = Stage.VALIDATION, notes = listOf("scope agreed"))

        // when — every attempt burns a whole stage budget before it escalates
        val actuals = stall(task, times = (RetryState.STAGE_MAX + 1) * (RetryState.TASK_MAX + 1))

        // then
        assertEquals(RetryState.TASK_MAX, actuals.count { it.retryOutcome is RetryOutcome.Restarted })
        val gaveUp = assertIs<RetryOutcome.GaveUp>(actuals.last().retryOutcome)
        assertEquals(RetryReason.NO_MARKER, gaveUp.reason)
        assertEquals(RetryState.TASK_MAX, gaveUp.task.taskRetryState.attempt)
    }

    @Test
    fun `when the provider is unreachable - then only the transport budget is spent`() {
        // given
        val task = task(stage = Stage.PLANNING, notes = listOf("scope agreed"))

        // when
        val actual = machine.update(task, UpdateReason.TransportFailed)

        // then
        val expected = task.copy(transportRetryState = RetryState(attempt = 1, max = RetryState.TRANSPORT_MAX))
        assertIs<RetryOutcome.Retried>(actual.retryOutcome)
        assertEquals(expected, actual.task)
    }

    @Test
    fun `when transport failures reach their budget - then the run gives up without restarting`() {
        // given
        val task = task(stage = Stage.PLANNING, notes = listOf("scope agreed"))

        // when
        val actuals = repeat(task, UpdateReason.TransportFailed, times = RetryState.TRANSPORT_MAX + 1)

        // then
        actuals.dropLast(1).forEachIndexed { index, actual ->
            assertIs<RetryOutcome.Retried>(actual.retryOutcome, "failure #${index + 1}")
            assertEquals(index + 1, actual.task.transportRetryState.attempt, "transport(failure #${index + 1})")
        }
        actuals.last().let { actual ->
            val gaveUp = assertIs<RetryOutcome.GaveUp>(actual.retryOutcome)
            assertEquals(RetryReason.TRANSPORT_FAILED, gaveUp.reason)
            assertEquals(Stage.PLANNING, actual.task.stage)
            assertEquals(0, actual.task.taskRetryState.attempt)
        }
    }

    /** [times] turns that name no stage at all, each fed the task the previous one left. */
    private fun stall(task: Task, times: Int): List<UpdateDecision> =
        repeat(task, UpdateReason.NoStageProposed, times)

    private fun repeat(task: Task, reason: UpdateReason, times: Int): List<UpdateDecision> =
        (1..times).fold(emptyList()) { decisions, _ ->
            decisions + machine.update(decisions.lastOrNull()?.task ?: task, reason)
        }
}

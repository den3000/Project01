package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.RetryLevel
import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachineImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TaskRetryTest {

    private val machine = TaskStateMachineImpl()

    @Test
    fun `when a stage-level reason is retried - then only the stage budget is spent`() {
        // given
        val task = task(stage = Stage.PLANNING, notes = listOf("scope agreed"))
        val reasons = RetryReason.entries.filter { it.level == RetryLevel.STAGE }
        require(reasons.isNotEmpty()) { "no STAGE-level reasons to cover" }

        // when
        val actuals = reasons.map { reason -> reason to machine.retry(task, reason) }

        // then
        val expected = task.copy(stageRetryState = RetryState(attempt = 1, max = RetryState.STAGE_MAX))
        actuals.forEach { (reason, actual) ->
            assertIs<RetryOutcome.Retried>(actual, "outcome($reason)")
            assertEquals(expected, actual.task, "task($reason)")
        }
    }

    @Test
    fun `when a stage is retried to its budget - then the next retry restarts the task`() {
        // given
        val reason = RetryReason.NO_MARKER
        val task = task(
            stage = Stage.VALIDATION,
            notes = listOf("scope agreed"),
            transportRetryState = RetryState(attempt = 2, max = RetryState.TRANSPORT_MAX),
        )

        // when
        val initial = machine.retry(task, reason)
        val actuals = (1..RetryState.STAGE_MAX).runningFold(initial) { prev, _ ->
            machine.retry(prev.task, reason)
        }

        // then
        actuals.dropLast(1).forEachIndexed { index, actual ->
            assertIs<RetryOutcome.Retried>(actual, "retry #${index + 1}")
            assertEquals(task.copy(stageRetryState = RetryState(index + 1, RetryState.STAGE_MAX)), actual.task)
        }
        actuals.last().let { actual ->
            assertIs<RetryOutcome.Restarted>(actual)
            assertEquals(task.copy(
                stage = Stage.CLARIFICATION,
                deepestStage = Stage.CLARIFICATION,
                taskRetryState = RetryState(attempt = 1, max = RetryState.TASK_MAX),
                stageRetryState = RetryState.stage(),
            ), actual.task)
        }
    }

    @Test
    fun `when a task-level reason is retried - then the task restarts`() {
        // given
        val task = task(
            stage = Stage.VALIDATION,
            notes = listOf("scope agreed"),
            stageRetryState = RetryState(attempt = 4, max = RetryState.STAGE_MAX),
            transportRetryState = RetryState(attempt = 2, max = RetryState.TRANSPORT_MAX),
        )
        val reasons = RetryReason.entries.filter { it.level == RetryLevel.TASK }
        require(reasons.isNotEmpty()) { "no TASK-level reasons to cover" }

        // when
        val actuals = reasons.map { reason -> reason to machine.retry(task, reason) }

        // then
        val expected = task.copy(
            stage = Stage.CLARIFICATION,
            deepestStage = Stage.CLARIFICATION,
            taskRetryState = RetryState(attempt = 1, max = RetryState.TASK_MAX),
            stageRetryState = RetryState.stage(),
        )
        actuals.forEach { (reason, actual) ->
            assertIs<RetryOutcome.Restarted>(actual, "outcome($reason)")
            assertEquals(expected, actual.task, "task($reason)")
        }
    }

    @Test
    fun `when a task is restarted to its budget - then the next failure gives up`() {
        // given
        val reason = RetryReason.TASK_STALLED
        val task = task(
            stage = Stage.VALIDATION,
            notes = listOf("scope agreed"),
            transportRetryState = RetryState(attempt = 2, max = RetryState.TRANSPORT_MAX),
        )

        // when
        val initial = machine.retry(task, reason)
        val actuals = (1..RetryState.TASK_MAX).runningFold(initial) { prev, _ ->
            machine.retry(prev.task, reason)
        }

        // then
        actuals.dropLast(1).forEachIndexed { index, actual ->
            assertIs<RetryOutcome.Restarted>(actual, "restart #${index + 1}")
            assertEquals(
                task.copy(
                    stage = Stage.CLARIFICATION,
                    deepestStage = Stage.CLARIFICATION,
                    taskRetryState = RetryState(index + 1, RetryState.TASK_MAX),
                ),
                actual.task,
            )
        }
        actuals.last().let { actual ->
            assertIs<RetryOutcome.GaveUp>(actual)
            assertEquals(reason, actual.reason)
            assertEquals(
                task.copy(
                    stage = Stage.CLARIFICATION,
                    deepestStage = Stage.CLARIFICATION,
                    taskRetryState = RetryState(RetryState.TASK_MAX, RetryState.TASK_MAX),
                ),
                actual.task,
            )
        }
    }

    @Test
    fun `when a transport-level reason is retried - then only the transport budget is spent`() {
        // given
        val task = task(stage = Stage.PLANNING, notes = listOf("scope agreed"))
        val reasons = RetryReason.entries.filter { it.level == RetryLevel.TRANSPORT }
        require(reasons.isNotEmpty()) { "no TRANSPORT-level reasons to cover" }

        // when
        val actuals = reasons.map { reason -> reason to machine.retry(task, reason) }

        // then
        val expected = task.copy(
            transportRetryState = RetryState(attempt = 1, max = RetryState.TRANSPORT_MAX),
        )
        actuals.forEach { (reason, actual) ->
            assertIs<RetryOutcome.Retried>(actual, "outcome($reason)")
            assertEquals(expected, actual.task, "task($reason)")
        }
    }

    @Test
    fun `when transport failures reach their budget - then the next one gives up`() {
        // given
        val reason = RetryReason.TRANSPORT_FAILED
        val task = task(
            stage = Stage.PLANNING,
            notes = listOf("scope agreed"),
            stageRetryState = RetryState(attempt = 4, max = RetryState.STAGE_MAX),
        )

        // when
        val initial = machine.retry(task, reason)
        val actuals = (1..RetryState.TRANSPORT_MAX).runningFold(initial) { prev, _ ->
            machine.retry(prev.task, reason)
        }

        // then
        actuals.dropLast(1).forEachIndexed { index, actual ->
            assertIs<RetryOutcome.Retried>(actual, "failure #${index + 1}")
            assertEquals(
                task.copy(transportRetryState = RetryState(index + 1, RetryState.TRANSPORT_MAX)),
                actual.task,
            )
        }
        actuals.last().let { actual ->
            assertIs<RetryOutcome.GaveUp>(actual)
            assertEquals(reason, actual.reason)
            assertEquals(
                task.copy(
                    transportRetryState = RetryState(RetryState.TRANSPORT_MAX, RetryState.TRANSPORT_MAX),
                ),
                actual.task,
            )
        }
    }
}

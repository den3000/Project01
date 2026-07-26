package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TaskTransportRetryTest {

    @Test
    fun `when a transport failure is retried - then only the transport budget is spent`() {
        // given
        val task = task(stage = Stage.PLANNING, notes = listOf("scope agreed"))
        val expected = task.copy(
            transportRetryState = RetryState(attempt = 1, max = RetryState.TRANSPORT_MAX),
        )

        // when
        val actual = TaskStateMachine.retry(task, RetryReason.TRANSPORT_FAILED)

        // then
        assertIs<RetryOutcome.Retried>(actual)
        assertEquals(expected, actual.task)
    }

    @Test
    fun `when the transport budget runs out - then the run gives up without restarting`() {
        // given
        val task = task(transportRetryState = spentToTheLast(RetryState.transport()))

        // when
        val actual = TaskStateMachine.retry(task, RetryReason.TRANSPORT_FAILED)

        // then
        assertIs<RetryOutcome.GaveUp>(actual)
        assertEquals(RetryReason.TRANSPORT_FAILED, actual.reason)
        assertEquals(task, actual.task)
    }

    @Test
    fun `when the task restarts - then the transport budget carries over`() {
        // given
        val transportRetryStateAttempt = 3
        val task = task(
            transportRetryState = RetryState(
                attempt = transportRetryStateAttempt,
                max = RetryState.TRANSPORT_MAX,
            ),
        )

        // when
        val actual = TaskStateMachine.retry(task, RetryReason.TASK_STALLED)

        // then
        assertIs<RetryOutcome.Restarted>(actual)
        assertEquals(transportRetryStateAttempt, actual.task.transportRetryState.attempt)
    }
}

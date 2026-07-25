package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TaskTransportRetryTest {

    @Test
    fun `when a transport failure is retried - then only the transport budget is spent`() {
        // given
        val task = task()

        // when
        val actual = TaskStateMachine.retry(task, RetryReason.TRANSPORT_FAILED)

        // then
        assertIs<RetryOutcome.Retried>(actual)
        assertEquals(1, actual.task.transportRetryState.attempt)
        assertEquals(0, actual.task.turnRetryState.attempt)
        assertEquals(0, actual.task.taskRetryState.attempt)
    }

    @Test
    fun `when the transport budget runs out - then the run gives up without restarting`() {
        // given
        // A dead provider is the one failure a restart cannot help: starting over
        // points the same unreachable endpoint at the same task from the top.
        val task = task(transportRetryState = spentToTheLast(RetryState.transport()))

        // when
        val actual = TaskStateMachine.retry(task, RetryReason.TRANSPORT_FAILED)

        // then
        assertIs<RetryOutcome.GaveUp>(actual)
        assertEquals(RetryReason.TRANSPORT_FAILED, actual.reason)
        assertEquals(0, actual.task.taskRetryState.attempt)
    }

    @Test
    fun `when the task restarts - then the transport budget carries over`() {
        // given
        // It counts an outage, and an outage does not care that the task started
        // over — refilling it would let a down provider be hammered five times as long.
        val task = task(transportRetryState = RetryState(attempt = 3, max = RetryState.TRANSPORT_MAX))

        // when
        val actual = TaskStateMachine.retry(task, RetryReason.TASK_STALLED)

        // then
        assertIs<RetryOutcome.Restarted>(actual)
        assertEquals(3, actual.task.transportRetryState.attempt)
    }
}

package ru.den.writes.code.agenticHub.cliJvm.plain

import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The task-FSM verdict line on stderr. */
class FsmPlainViewTest {

    @Test
    fun `when restarted - then the line names the attempt that is starting`() {
        assertEquals(
            listOf("[fsm] task restarted from the top — attempt 3"),
            FsmPlainView(RetryOutcome.Restarted(task(taskAttempt = 2))).stderr(),
        )
    }

    @Test
    fun `when gave up - then the line names the stage it died at and the reason`() {
        assertEquals(
            listOf("[fsm] task gave up at execution — out of attempts (NO_MARKER)"),
            FsmPlainView(RetryOutcome.GaveUp(task(), RetryReason.NO_MARKER)).stderr(),
        )
    }

    @Test
    fun `when retried - then nothing`() {
        assertTrue(FsmPlainView(RetryOutcome.Retried(task())).stderr().isEmpty())
    }

    private fun task(taskAttempt: Int = 0) = Task(
        taskId = "t",
        stage = Stage.EXECUTION,
        taskRetryState = RetryState(attempt = taskAttempt, max = RetryState.TASK_MAX),
    )
}

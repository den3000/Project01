package ru.den.writes.code.agenticHub.cliJvm.tui

import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The task-FSM verdict line in the TUI column — the same three outcomes the plain front renders. */
class FsmTuiViewTest {

    @Test
    fun `when restarted - then the line names the attempt that is starting`() {
        assertEquals(
            "task restarted from the top — attempt 3",
            fsmLine(RetryOutcome.Restarted(task(taskAttempt = 2))),
        )
    }

    @Test
    fun `when gave up - then the line names the stage it died at and the reason`() {
        assertEquals(
            "task gave up at execution — out of attempts (NO_MARKER)",
            fsmLine(RetryOutcome.GaveUp(task(), RetryReason.NO_MARKER)),
        )
    }

    @Test
    fun `when retried - then nothing`() {
        assertNull(fsmLine(RetryOutcome.Retried(task())))
    }

    private fun task(taskAttempt: Int = 0) = Task(
        taskId = "t",
        stage = Stage.EXECUTION,
        taskRetryState = RetryState(attempt = taskAttempt, max = RetryState.TASK_MAX),
    )
}

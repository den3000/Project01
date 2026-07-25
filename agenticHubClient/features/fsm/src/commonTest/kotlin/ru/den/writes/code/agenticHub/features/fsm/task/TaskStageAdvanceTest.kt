package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.AdvanceOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TaskStageAdvanceTest {

    @Test
    fun `when a legal move is proposed - then the stage advances`() {
        // given
        val task = task(stage = Stage.PLANNING)

        // when
        val actual = TaskStateMachine.advance(task, Stage.EXECUTION)

        // then
        assertIs<AdvanceOutcome.Advanced>(actual)
        assertEquals(Stage.PLANNING, actual.from)
        assertEquals(Stage.EXECUTION, actual.to)
        assertEquals(Stage.EXECUTION, actual.task.stage)
        assertNull(actual.reason)
    }

    @Test
    fun `when the stage advances - then both inner budgets start over`() {
        // given
        // They measure what THIS stage cost; a task that honestly spends four
        // turns and a dozen rewrites per stage has never stalled and must not
        // restart. A run that is still advancing is not thrown away.
        val task = task(
            stage = Stage.PLANNING,
            stageRetryState = RetryState(attempt = 4, max = STAGE_MAX),
            turnRetryState = RetryState(attempt = 12, max = TURN_MAX),
            transportRetryState = RetryState(attempt = 3, max = TRANSPORT_MAX),
        )

        // when
        val actual = TaskStateMachine.advance(task, Stage.EXECUTION)

        // then
        assertIs<AdvanceOutcome.Advanced>(actual)
        assertEquals(RetryState.stage(), actual.task.stageRetryState)
        assertEquals(RetryState.turn(), actual.task.turnRetryState)
        // the outage budget is not an inner one — progress does not refill it
        assertEquals(3, actual.task.transportRetryState.attempt)
    }

    @Test
    fun `when the stage advances - then the restarts already spent still stand`() {
        // given
        // Reaching a new stage is progress within the attempt, not a new attempt.
        val task = task(stage = Stage.PLANNING, taskRetryState = RetryState(attempt = 2, max = TASK_MAX))

        // when
        val actual = TaskStateMachine.advance(task, Stage.EXECUTION)

        // then
        assertIs<AdvanceOutcome.Advanced>(actual)
        assertEquals(2, actual.task.taskRetryState.attempt)
    }

    @Test
    fun `when a task with no stage is moved - then any stage initializes it`() {
        // given
        // A hand-edited or freshly created task: no prior stage to violate.
        val task = task(stage = null)

        // when - then
        Stage.entries.forEach { to ->
            val actual = TaskStateMachine.advance(task, to)
            assertIs<AdvanceOutcome.Advanced>(actual, "advance(null -> $to)")
            assertEquals(to, actual.task.stage, "stage(null -> $to)")
        }
    }

    @Test
    fun `when the current stage is proposed again - then nothing moves`() {
        // given
        // The marker names the stage being moved TO; used as a label it does
        // nothing, and swallowing that in silence is the FSM's main lock.
        val task = task(stage = Stage.EXECUTION)

        // when
        val actual = TaskStateMachine.advance(task, Stage.EXECUTION)

        // then
        assertIs<AdvanceOutcome.Repeated>(actual)
        assertEquals(Stage.EXECUTION, actual.stage)
        assertEquals(setOf(Stage.VALIDATION, Stage.PLANNING), actual.allowed)
        assertEquals(task, actual.task)
        assertEquals(RetryReason.STAGE_REPEATED, actual.reason)
    }

    @Test
    fun `when a skipping move is proposed - then it is refused and the stage held`() {
        // given
        // The guarantee the prompt cannot give: a model asked to skip a stage will.
        val task = task(stage = Stage.PLANNING)

        // when
        val actual = TaskStateMachine.advance(task, Stage.DONE)

        // then
        assertIs<AdvanceOutcome.Rejected>(actual)
        assertEquals(Stage.PLANNING, actual.from)
        assertEquals(Stage.DONE, actual.proposed)
        assertEquals(setOf(Stage.EXECUTION, Stage.CLARIFICATION), actual.allowed)
        assertEquals(task, actual.task)
        assertEquals(RetryReason.STAGE_REJECTED, actual.reason)
    }

    @Test
    fun `when a paused task is moved - then the proposal is held and costs nothing`() {
        // given
        // Pause is a standing instruction to hold the stage; charging a retry for
        // obeying it would restart a task that is doing what it was told.
        val task = task(stage = Stage.EXECUTION, paused = true)

        // when
        val actual = TaskStateMachine.advance(task, Stage.VALIDATION)

        // then
        assertIs<AdvanceOutcome.Held>(actual)
        assertEquals(task, actual.task)
        assertNull(actual.reason)
    }
}

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
    fun `when a fresh task is moved - then it advances off clarification like any other`() {
        // given
        // A task with nothing set yet still starts inside the machine, so it gets
        // no free jump: from the initial stage only planning is reachable.
        val task = task(stage = Stage.INITIAL)

        // when - then
        Stage.entries.forEach { to ->
            val actual = TaskStateMachine.advance(task, to)
            assertEquals(to == Stage.PLANNING, actual is AdvanceOutcome.Advanced, "advance(initial -> $to)")
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

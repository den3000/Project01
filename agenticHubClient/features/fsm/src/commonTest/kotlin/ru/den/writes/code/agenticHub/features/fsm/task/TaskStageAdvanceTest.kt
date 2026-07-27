package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.AdvanceOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.Task
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachineImpl
import ru.den.writes.code.agenticHub.features.fsm.UpdateDecision
import ru.den.writes.code.agenticHub.features.fsm.UpdateReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a proposed stage does to the task: the move itself and what it costs. Driven through
 * `update`, the way the engine drives it, so a move and its price are read from one answer.
 */
class TaskStageAdvanceTest {

    private val machine = TaskStateMachineImpl()

    @Test
    fun `when a legal move is proposed - then the stage advances`() {
        // given
        val stage = Stage.PLANNING
        val proposed = Stage.EXECUTION
        val task = task(stage = stage)

        // when
        val actual = propose(task, proposed)

        // then
        val advance = assertIs<AdvanceOutcome.Advanced>(actual.advance)
        assertEquals(stage, advance.from)
        assertEquals(proposed, advance.to)
        assertEquals(proposed, actual.task.stage)
        assertNull(actual.retryReason)
    }

    @Test
    fun `when the stage advances - then only the stage budget starts over`() {
        // given
        val task = task(
            stage = Stage.PLANNING,
            notes = listOf("scope agreed"),
            taskRetryState = RetryState(attempt = 2, max = RetryState.TASK_MAX),
            stageRetryState = RetryState(attempt = 4, max = RetryState.STAGE_MAX),
            transportRetryState = RetryState(attempt = 3, max = RetryState.TRANSPORT_MAX),
        )
        val proposed = Stage.EXECUTION

        // when
        val actual = propose(task, proposed)

        // then
        val expected = task.copy(
            stage = proposed,
            deepestStage = proposed,
            stageRetryState = RetryState.stage(),
        )
        assertEquals(expected, actual.task)
    }

    @Test
    fun `when a task is created without a stage - then it starts at clarification`() {
        // given
        val task = Task(taskId = TASK_ID)

        // when
        val actual = task.stage

        // then
        assertEquals(Stage.CLARIFICATION, actual)
    }

    @Test
    fun `when a fresh task is moved to done - then the jump is refused like any other`() {
        // given
        val task = Task(taskId = TASK_ID)
        val proposed = Stage.DONE

        // when
        val actual = propose(task, proposed)

        // then
        val advance = assertIs<AdvanceOutcome.Rejected>(actual.advance)
        assertEquals(Stage.CLARIFICATION, advance.from)
        assertEquals(proposed, advance.proposed)
        assertEquals(setOf(Stage.PLANNING), advance.allowed)
        assertEquals(Stage.CLARIFICATION, actual.task.stage)
    }

    @Test
    fun `when a fresh task is moved to planning - then it advances`() {
        // given
        val task = Task(taskId = TASK_ID)
        val proposed = Stage.PLANNING

        // when
        val actual = propose(task, proposed)

        // then
        val advance = assertIs<AdvanceOutcome.Advanced>(actual.advance)
        assertEquals(Stage.CLARIFICATION, advance.from)
        assertEquals(proposed, actual.task.stage)
    }

    @Test
    fun `when a move revisits ground already reached - then it is charged and the budget is not refreshed`() {
        // given
        val spent = RetryState(attempt = 6, max = RetryState.STAGE_MAX)
        val task = task(stage = Stage.VALIDATION, deepestStage = Stage.VALIDATION, stageRetryState = spent)

        // when
        val actual = propose(task, Stage.EXECUTION)

        // then — the move applies, and the turn costs one attempt on the standing budget
        val advance = assertIs<AdvanceOutcome.Advanced>(actual.advance)
        assertFalse(advance.newGround)
        assertEquals(RetryReason.STAGE_REVISITED, actual.retryReason)
        assertEquals(spent.attempt + 1, actual.task.stageRetryState.attempt)
        assertEquals(Stage.VALIDATION, actual.task.deepestStage)
    }

    @Test
    fun `when a move goes forward onto ground already reached - then it is charged like a step back`() {
        // given
        val spent = RetryState(attempt = 6, max = RetryState.STAGE_MAX)
        val task = task(stage = Stage.EXECUTION, deepestStage = Stage.VALIDATION, stageRetryState = spent)

        // when — forward on the table, but the task has stood on validation before
        val actual = propose(task, Stage.VALIDATION)

        // then
        val advance = assertIs<AdvanceOutcome.Advanced>(actual.advance)
        assertFalse(advance.newGround)
        assertEquals(RetryReason.STAGE_REVISITED, actual.retryReason)
        assertEquals(spent.attempt + 1, actual.task.stageRetryState.attempt)
        assertEquals(Stage.VALIDATION, actual.task.deepestStage)
    }

    @Test
    fun `when a move goes onto new ground - then it costs nothing and the stage budget starts over`() {
        // given
        val spent = RetryState(attempt = 4, max = RetryState.STAGE_MAX)
        val task = task(stage = Stage.PLANNING, deepestStage = Stage.PLANNING, stageRetryState = spent)

        // when
        val actual = propose(task, Stage.EXECUTION)

        // then
        val advance = assertIs<AdvanceOutcome.Advanced>(actual.advance)
        assertTrue(advance.newGround)
        assertNull(actual.retryReason)
        assertEquals(RetryState.stage(), actual.task.stageRetryState)
    }

    @Test
    fun `when the depth was never recorded - then the stage the task stands on counts as reached`() {
        // given
        // A task built or loaded without a depth: standing at validation means validation
        // has been reached, whatever the field happens to hold.
        val spent = RetryState(attempt = 6, max = RetryState.STAGE_MAX)
        val task = Task(taskId = TASK_ID, stage = Stage.VALIDATION, stageRetryState = spent)

        // when
        val actual = propose(task, Stage.EXECUTION)

        // then — no free refresh: the step back is charged like any other revisit
        assertEquals(RetryReason.STAGE_REVISITED, actual.retryReason)
        assertEquals(spent.attempt + 1, actual.task.stageRetryState.attempt)
    }

    @Test
    fun `when the current stage is proposed again - then nothing moves`() {
        // given
        val stage = Stage.EXECUTION
        val task = task(stage = stage)

        // when
        val actual = propose(task, stage)

        // then
        val advance = assertIs<AdvanceOutcome.Repeated>(actual.advance)
        assertEquals(stage, advance.stage)
        assertEquals(setOf(Stage.VALIDATION, Stage.PLANNING), advance.allowed)
        assertEquals(stage, actual.task.stage)
        assertEquals(RetryReason.STAGE_REPEATED, actual.retryReason)
    }

    @Test
    fun `when a skipping move is proposed - then it is refused and the stage held`() {
        // given
        val stage = Stage.PLANNING
        val proposed = Stage.DONE
        val task = task(stage = stage)

        // when
        val actual = propose(task, proposed)

        // then
        val advance = assertIs<AdvanceOutcome.Rejected>(actual.advance)
        assertEquals(stage, advance.from)
        assertEquals(proposed, advance.proposed)
        assertEquals(setOf(Stage.EXECUTION, Stage.CLARIFICATION), advance.allowed)
        assertEquals(stage, actual.task.stage)
        assertEquals(RetryReason.STAGE_REJECTED, actual.retryReason)
    }

    private fun propose(task: Task, stage: Stage): UpdateDecision =
        machine.update(task, UpdateReason.StageProposed(stage))
}

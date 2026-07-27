package ru.den.writes.code.agenticHub.features.lifecycle.session

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.Task
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.toFsmTask
import ru.den.writes.code.agenticHub.features.llm.FakeLlmScript
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What one turn costs the task, read back off disk and through the same mapping the engine
 * uses: a budget that is decided but never persisted is not a budget, and it is the FSM's
 * [Task] that says what was spent — `stageRetriesSpent` in the file is only how it is stored.
 *
 * Only the price is asserted here. Where the stage ends up is `TurnEngineConformanceTest`'s,
 * and it checks that on both engines; what a move is worth is `features:fsm`'s own tests. What
 * neither of them can see is whether the charge survives the trip to disk — that is this file,
 * and three answers cover it: one that pays without moving, one that moves and still pays, one
 * that moves deeper and pays nothing.
 */
class FsmTurnEngineChargeTest {

    @Test
    fun `when the reply carries no marker - then the stage pays for the turn`() = runTest {
        // given
        val api = scriptedApi(FakeLlmScript().apply { queueText("thinking out loud") })
        val task = taskStandingAt(TaskStage.EXECUTION)

        // when
        withTurnEngine({ api }, engineUnderTest = FSM_ENGINE, task = task) {
            engine.turn("one")

            // then
            val actual = fsmTask()
            assertEquals(Stage.EXECUTION, actual.stage)
            assertEquals(1, actual.stageRetryState.attempt)
        }
    }

    @Test
    fun `when the move goes back over covered ground - then the stage pays for the turn`() = runTest {
        // given
        val api = scriptedApi(FakeLlmScript().apply { queueText("re-planning [[stage:planning]]") })
        val task = taskStandingAt(TaskStage.EXECUTION)

        // when
        withTurnEngine({ api }, engineUnderTest = FSM_ENGINE, task = task) {
            engine.turn("one")

            // then
            val actual = fsmTask()
            assertEquals(Stage.PLANNING, actual.stage)
            assertEquals(1, actual.stageRetryState.attempt)
        }
    }

    @Test
    fun `when the move reaches a deeper stage - then the turn is free`() = runTest {
        // given
        val api = scriptedApi(FakeLlmScript().apply { queueText("checking it [[stage:validation]]") })
        val task = taskStandingAt(TaskStage.EXECUTION)

        // when
        withTurnEngine({ api }, engineUnderTest = FSM_ENGINE, task = task) {
            engine.turn("one")

            // then
            val actual = fsmTask()
            assertEquals(Stage.VALIDATION, actual.stage)
            assertEquals(Stage.VALIDATION, actual.deepestStage)
            assertEquals(0, actual.stageRetryState.attempt)
        }
    }

    /** The task as the FSM sees it, straight off disk through the engine's own mapping. */
    private fun TurnEngineFixture.fsmTask(): Task =
        checkNotNull(memStore.loadTask(TASK_ID)) { "task $TASK_ID is not on disk" }.toFsmTask()

    /** A task standing at [stage] with nothing spent and nothing deeper reached. */
    private fun taskStandingAt(stage: TaskStage) =
        TaskNotes(taskId = TASK_ID, stage = stage, deepestStage = stage)

    private companion object {
        const val TASK_ID = "t"
    }
}

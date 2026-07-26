package ru.den.writes.code.agenticHub.features.lifecycle.session

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.llm.FakeLlmScript
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What one turn costs the task, read off the task file rather than off the return value: a
 * budget that is decided but never persisted is not a budget.
 *
 * Every case here is one turn on a task standing at execution, differing only in what the
 * model answered — no marker, a step back, a step deeper — because that is exactly what the
 * price is supposed to depend on.
 */
class FsmTurnEngineChargeTest {

    @Test
    fun `when the reply carries no marker - then the stage pays for the turn`() = runTest {
        // given
        val api = scriptedApi(FakeLlmScript().apply { queueText("thinking out loud") })

        // when
        withTurnEngine({ api }, engineUnderTest = FSM_ENGINE, task = task()) {
            engine.turn("one")

            // then
            assertEquals(1, spentOn(TASK_ID))
            assertEquals(TaskStage.EXECUTION, stageOf(TASK_ID))
        }
    }

    @Test
    fun `when the move goes back over covered ground - then the stage pays for the turn`() = runTest {
        // given
        val api = scriptedApi(FakeLlmScript().apply { queueText("re-planning [[stage:planning]]") })

        // when
        withTurnEngine({ api }, engineUnderTest = FSM_ENGINE, task = task()) {
            engine.turn("one")

            // then
            assertEquals(1, spentOn(TASK_ID))
            assertEquals(TaskStage.PLANNING, stageOf(TASK_ID))
        }
    }

    @Test
    fun `when the move reaches a deeper stage - then the turn is free`() = runTest {
        // given
        val api = scriptedApi(FakeLlmScript().apply { queueText("checking it [[stage:validation]]") })

        // when
        withTurnEngine({ api }, engineUnderTest = FSM_ENGINE, task = task()) {
            engine.turn("one")

            // then
            assertEquals(0, spentOn(TASK_ID))
            assertEquals(TaskStage.VALIDATION, stageOf(TASK_ID))
        }
    }

    @Test
    fun `when the reply names the stage it already stands on - then the stage pays for the turn`() = runTest {
        // given
        val api = scriptedApi(FakeLlmScript().apply { queueText("still here [[stage:execution]]") })

        // when
        withTurnEngine({ api }, engineUnderTest = FSM_ENGINE, task = task()) {
            engine.turn("one")

            // then
            assertEquals(1, spentOn(TASK_ID))
            assertEquals(TaskStage.EXECUTION, stageOf(TASK_ID))
        }
    }

    @Test
    fun `when the move skips a stage - then it is refused and the stage pays for the turn`() = runTest {
        // given
        val api = scriptedApi(FakeLlmScript().apply { queueText("all done [[stage:done]]") })

        // when
        withTurnEngine({ api }, engineUnderTest = FSM_ENGINE, task = task()) {
            engine.turn("one")

            // then
            assertEquals(1, spentOn(TASK_ID))
            assertEquals(TaskStage.EXECUTION, stageOf(TASK_ID))
        }
    }

    /** Stage attempts spent, straight off the task file. */
    private fun TurnEngineFixture.spentOn(taskId: String): Int =
        memStore.loadTask(taskId)?.stageRetriesSpent ?: -1

    /** A task in the middle of the machine, with nothing spent and nothing deeper reached. */
    private fun task() = TaskNotes(
        taskId = TASK_ID,
        stage = TaskStage.EXECUTION,
        deepestStage = TaskStage.EXECUTION,
    )

    private companion object {
        const val TASK_ID = "t"
    }
}

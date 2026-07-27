package ru.den.writes.code.agenticHub.features.lifecycle.session

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.TurnResult
import ru.den.writes.code.agenticHub.features.llm.FakeLlmScript
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import ru.den.writes.code.agenticHub.platform.database.DEFAULT_BRANCH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FsmTurnEngineRestartTest {

    @Test
    fun `when the stage budget is spent - then the stalled turn restarts the task`() = runTest {
        // given
        val api = scriptedApi(FakeLlmScript().apply { queueText("still going") })

        // when
        withTurnEngine({ api }, engineUnderTest = FSM_ENGINE, task = spentStageTask()) {
            val result = engine.turn("one")

            // then
            assertIs<RetryOutcome.Restarted>(assertIs<TurnResult.Ok>(result).retryOutcome)
            assertEquals(TaskStage.CLARIFICATION, stageOf(TASK_ID))
        }
    }

    @Test
    fun `when a restart happens - then the following turns go to a branch of their own`() = runTest {
        // given
        val api = scriptedApi(
            FakeLlmScript().apply {
                queueText("still going")
                queueText("starting over")
            },
        )

        // when
        withTurnEngine({ api }, engineUnderTest = FSM_ENGINE, task = spentStageTask()) {
            engine.turn("one")
            engine.turn("two")

            // then
            assertEquals(listOf(DEFAULT_BRANCH, "$DEFAULT_BRANCH-attempt-2"), branches())
        }
    }

    @Test
    fun `when a restart happens - then the next turn no longer carries the failed attempt`() = runTest {
        // given
        val script = FakeLlmScript().apply {
            queueText("still going")
            queueText("starting over")
        }
        val api = scriptedApi(script)

        // when
        withTurnEngine({ api }, engineUnderTest = FSM_ENGINE, task = spentStageTask()) {
            engine.turn("one")
            engine.turn("two")

            // then
            val wire = script.calls[1].messages
            assertTrue(wire.none { "still going" in it.text }, "the failed reply is still on the wire")
            assertTrue(wire.none { it.text == "one" }, "the failed prompt is still on the wire")
            assertTrue(wire.any { it.text == "two" })
        }
    }

    @Test
    fun `when a restart happens - then the failed attempt stays where a new session would find it`() = runTest {
        // given
        val api = scriptedApi(
            FakeLlmScript().apply {
                queueText("still going")
                queueText("starting over")
            },
        )

        // when
        withTurnEngine({ api }, engineUnderTest = FSM_ENGINE, task = spentStageTask()) {
            engine.turn("one")
            engine.turn("two")

            // then — the opening branch holds the failed turn and nothing after it, which is
            // also the limit of the mechanism: that branch is what a new session loads.
            assertEquals(listOf("one", "still going"), persistedMessages().map { it.text })
            assertEquals(4, persistedCount())
        }
    }

    @Test
    fun `when the restarts are spent too - then the run gives up and the branch stands`() = runTest {
        // given
        val api = scriptedApi(FakeLlmScript().apply { queueText("still going") })
        val task = spentStageTask().copy(taskRetriesSpent = RetryState.TASK_MAX)

        // when
        withTurnEngine({ api }, engineUnderTest = FSM_ENGINE, task = task) {
            val result = engine.turn("one")

            // then — nothing left to start over, so the conversation is left where it was
            assertIs<RetryOutcome.GaveUp>(assertIs<TurnResult.Ok>(result).retryOutcome)
            assertEquals(listOf(DEFAULT_BRANCH), branches())
        }
    }

    /**
     * A task one stalled turn away from a restart: the stage budget is gone, so the first turn
     * without a marker escalates instead of merely being charged.
     */
    private fun spentStageTask() = TaskNotes(
        taskId = TASK_ID,
        stage = TaskStage.EXECUTION,
        deepestStage = TaskStage.EXECUTION,
        stageRetriesSpent = RetryState.STAGE_MAX,
    )

    private companion object {
        const val TASK_ID = "t"
    }
}

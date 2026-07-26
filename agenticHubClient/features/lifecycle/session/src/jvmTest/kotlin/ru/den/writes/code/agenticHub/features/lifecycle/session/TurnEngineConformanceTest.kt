package ru.den.writes.code.agenticHub.features.lifecycle.session

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.StageAdvance
import ru.den.writes.code.agenticHub.features.llm.FakeLlmScript
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What every engine must do, whoever owns its FSM.
 *
 * The contract, not the habits: what the caller gets back, what reaches the history and
 * where the task ends up. Wording of the `[fsm]` lines, when a nudge is armed and which
 * budget paid for what are an implementation's own business and belong in its own tests —
 * the two engines already differ there on purpose.
 *
 * Every test runs on both engines and names the failing one, so a divergence points at
 * itself instead of at whichever engine the suite happened to be built with.
 */
class TurnEngineConformanceTest {

    @Test
    fun `when a turn succeeds - then the reply comes back and both sides persist`() = runTest {
        // given
        val engines = BOTH_ENGINES

        // when
        val actuals = engines.map { engine ->
            engine to runTurnEngineWith(
                llmApi = { scriptedApi(FakeLlmScript().apply { queueText("reply", promptTokens = 12) }) },
                engineUnderTest = engine,
                prompt = "hi",
            )
        }

        // then
        actuals.forEach { (engine, run) ->
            val result = run.ok()
            assertEquals("reply", result.reply, "reply($engine)")
            assertEquals(12, result.usage?.promptTokens, "usage($engine)")
            assertEquals(
                listOf(Message(Role.USER, "hi"), Message(Role.ASSISTANT, "reply")),
                run.persistedMessages,
                "persisted($engine)",
            )
        }
    }

    @Test
    fun `when a legal move is signalled - then the stage advances and is stored`() = runTest {
        // given
        val engines = BOTH_ENGINES

        // when
        val actuals = engines.map { engine ->
            engine to runTurnEngineWith(
                llmApi = { scriptedApi(FakeLlmScript().apply { queueText("done here [[stage:planning]]") }) },
                engineUnderTest = engine,
                task = SIMPLE_TASK,
            )
        }

        // then
        actuals.forEach { (engine, run) ->
            val advance = run.ok().stageAdvance
            assertIs<StageAdvance.Advanced>(advance, "outcome($engine)")
            assertEquals(TaskStage.PLANNING, advance.to, "to($engine)")
            assertEquals(TaskStage.PLANNING, run.finalStage, "stored($engine)")
        }
    }

    @Test
    fun `when a skipping move is signalled - then it is refused and the stage held`() = runTest {
        // given
        val engines = BOTH_ENGINES

        // when
        val actuals = engines.map { engine ->
            engine to runTurnEngineWith(
                llmApi = { scriptedApi(FakeLlmScript().apply { queueText("finished [[stage:done]]") }) },
                engineUnderTest = engine,
                task = SIMPLE_TASK,
            )
        }

        // then
        actuals.forEach { (engine, run) ->
            val advance = run.ok().stageAdvance
            assertIs<StageAdvance.Rejected>(advance, "outcome($engine)")
            assertEquals(TaskStage.DONE, advance.proposed, "proposed($engine)")
            assertEquals(SIMPLE_TASK.stage, run.finalStage, "held($engine)")
        }
    }

    @Test
    fun `when the current stage is signalled again - then nothing moves`() = runTest {
        // given
        val engines = BOTH_ENGINES

        // when
        val actuals = engines.map { engine ->
            engine to runTurnEngineWith(
                llmApi = { scriptedApi(FakeLlmScript().apply { queueText("still here [[stage:clarification]]") }) },
                engineUnderTest = engine,
                task = SIMPLE_TASK,
            )
        }

        // then
        actuals.forEach { (engine, run) ->
            assertIs<StageAdvance.Repeated>(run.ok().stageAdvance, "outcome($engine)")
            assertEquals(SIMPLE_TASK.stage, run.finalStage, "held($engine)")
        }
    }

    @Test
    fun `when the provider errors - then the turn fails and nothing persists`() = runTest {
        // given
        val engines = BOTH_ENGINES

        // when
        val actuals = engines.map { engine ->
            engine to runTurnEngineWith(
                llmApi = { scriptedApi(FakeLlmScript()) },
                engineUnderTest = engine,
                task = SIMPLE_TASK,
            )
        }

        // then
        actuals.forEach { (engine, run) ->
            assertIs<ru.den.writes.code.agenticHub.features.lifecycle.session.turn.TurnResult.Failed>(
                run.turnLogs.single().result,
                "outcome($engine)",
            )
            assertEquals(emptyList(), run.persistedMessages, "persisted($engine)")
        }
    }
}

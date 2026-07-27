package ru.den.writes.code.agenticHub.features.lifecycle.session.inline

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.lifecycle.session.INLINE_ENGINE
import ru.den.writes.code.agenticHub.features.lifecycle.session.SIMPLE_TASK
import ru.den.writes.code.agenticHub.features.lifecycle.session.runTurnEngineWith
import ru.den.writes.code.agenticHub.features.lifecycle.session.scriptedApi
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.StageAdvance
import ru.den.writes.code.agenticHub.features.llm.FakeLlmScript
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What the inline engine must do at all: what the caller gets back, what reaches the history
 * and where the task ends up. Habits — wording of the `[fsm]` lines, when the nudge is armed —
 * are in `InlineFsmTurnEngineTest` next door.
 *
 * Used to run on both engines. It stopped being worth it once the FSM engine got tests of its
 * own: the same five cases on two implementations proved that both were alive, not that either
 * was right, and the engines differ on purpose in everything the contract does not name.
 */
class InlineFsmTurnEngineContractTest {

    @Test
    fun `when a turn succeeds - then the reply comes back and both sides persist`() = runTest {
        // given
        val engines = listOf(INLINE_ENGINE)

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
        val engines = listOf(INLINE_ENGINE)

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
        val engines = listOf(INLINE_ENGINE)

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
        val engines = listOf(INLINE_ENGINE)

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
        val engines = listOf(INLINE_ENGINE)

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

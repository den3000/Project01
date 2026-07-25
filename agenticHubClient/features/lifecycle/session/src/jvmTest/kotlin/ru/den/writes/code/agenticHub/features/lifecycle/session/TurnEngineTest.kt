package ru.den.writes.code.agenticHub.features.lifecycle.session

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.StageAdvance
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.TurnResult
import ru.den.writes.code.agenticHub.features.llm.FakeLlmScript
import ru.den.writes.code.agenticHub.features.llm.LlmResult
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.memory.ProfileSection
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Offline tests for the turn engine: persistence, the [TurnResult] it returns, and the
 * task-stage FSM outcome. No I/O is asserted (the engine doesn't print) and no model is
 * called — replies come from a scripted fake, so every assertion is deterministic.
 *
 * Environment comes from `TurnEngineTestSupport.kt`; the live stand shares it, which is
 * what keeps both suites pointed at the same engine wiring.
 */
class TurnEngineTest {

    //region ход и персист

    @Test
    fun `when a turn succeeds - then both sides persist and Ok carries the snapshot`() = runTest {
        // given
        val script = FakeLlmScript().apply { queueText("reply", promptTokens = 12, outputTokens = 3) }

        withTurnEngine({ scriptedApi(script) }) {
            // when
            val result = engine.turn("hi")

            // then
            assertTrue(result is TurnResult.Ok)
            assertEquals("reply", result.reply)
            assertEquals(12, result.usage?.promptTokens)
            assertEquals(StageAdvance.None, result.stageAdvance)
            assertEquals(1, result.session?.turns)
            assertEquals(
                listOf(Message(Role.USER, "hi"), Message(Role.ASSISTANT, "reply")),
                persistedMessages(),
            )
        }
    }

    @Test
    fun `when the provider errors - then Failed and nothing persisted`() = runTest {
        // given — an empty queue makes the fake answer with its synthetic error
        val script = FakeLlmScript()

        withTurnEngine({ scriptedApi(script) }) {
            // when
            val result = engine.turn("hi")

            // then
            assertEquals(TurnResult.Failed("FakeLlmScript: no scripted response"), result)
            assertEquals(0, persistedCount())
        }
    }

    @Test
    fun `when the reply is empty with no usage - then Failed with that reason`() = runTest {
        // given
        val script = FakeLlmScript().apply { queue(LlmResult(text = null)) }

        withTurnEngine({ scriptedApi(script) }) {
            // when
            val result = engine.turn("hi")

            // then
            assertEquals(TurnResult.Failed("empty response with no usage"), result)
        }
    }

    //endregion

    //region переходы стадий

    @Test
    fun `when the reply signals a legal stage move - then Advanced and the task is saved`() = runTest {
        // given
        val script = FakeLlmScript().apply { queueText("on it [[stage:execution]]") }

        withTurnEngine({ scriptedApi(script) }, task = TaskNotes("t", stage = TaskStage.PLANNING)) {
            // when
            val result = engine.turn("hi")

            // then
            assertEquals(
                StageAdvance.Advanced(TaskStage.PLANNING, TaskStage.EXECUTION),
                (result as TurnResult.Ok).stageAdvance,
            )
            assertEquals(TaskStage.EXECUTION, stageOf("t"))
        }
    }

    @Test
    fun `when the reply signals an illegal stage move - then Rejected and the task is unchanged`() = runTest {
        // given — DONE isn't reachable from PLANNING
        val script = FakeLlmScript().apply { queueText("skip ahead [[stage:done]]") }

        withTurnEngine({ scriptedApi(script) }, task = TaskNotes("t", stage = TaskStage.PLANNING)) {
            // when
            val result = engine.turn("hi")

            // then
            val advance = (result as TurnResult.Ok).stageAdvance
            assertTrue(advance is StageAdvance.Rejected)
            assertEquals(TaskStage.PLANNING, advance.from)
            assertEquals(TaskStage.DONE, advance.proposed)
            assertEquals(TaskStage.PLANNING, stageOf("t"))
        }
    }

    @Test
    fun `when the reply signals the stage it is already in - then Repeated and the task is unchanged`() = runTest {
        // given — the model uses the marker to label where it is, not to name a destination
        val script = FakeLlmScript().apply { queueText("still checking [[stage:validation]]") }

        withTurnEngine({ scriptedApi(script) }, task = TaskNotes("t", stage = TaskStage.VALIDATION)) {
            // when
            val result = engine.turn("hi")

            // then
            val advance = (result as TurnResult.Ok).stageAdvance
            assertTrue(advance is StageAdvance.Repeated)
            assertEquals(TaskStage.VALIDATION, advance.stage)
            assertEquals(setOf(TaskStage.DONE, TaskStage.EXECUTION), advance.allowed)
            assertEquals(TaskStage.VALIDATION, stageOf("t"))
        }
    }

    //endregion

    //region обратная связь FSM на следующем ходу

    @Test
    fun `when a re-signalled stage precedes a turn - then the next turn's wire carries the no-move note once`() =
        runTest {
            // given — turn 1 re-signals VALIDATION; turn 2 answers without any marker
            val script = FakeLlmScript().apply {
                queueText("still checking [[stage:validation]]")
                queueText("wrapping up")
                queueText("more")
            }

            withTurnEngine({ scriptedApi(script) }, task = TaskNotes("t", stage = TaskStage.VALIDATION)) {
                // when
                engine.turn("go")
                engine.turn("go")
                engine.turn("go")

                // then — on the very next turn, quoting the marker back and naming both exits
                val note = script.calls[1].messages.firstOrNull { it.role == Role.SYSTEM && "[fsm] no move:" in it.text }
                assertNotNull(note)
                assertTrue("[[stage:validation]]" in note.text)
                assertTrue("done" in note.text)
                assertTrue("execution" in note.text)
                // consumed once: turn 2 signalled nothing at all, so turn 3 carries no note
                assertTrue(script.calls[2].messages.none { it.role == Role.SYSTEM && "[fsm]" in it.text })
            }
        }

    @Test
    fun `when a rejected stage move precedes a turn - then the next turn's wire carries the FSM signal once`() =
        runTest {
            // given — PLANNING → DONE is rejected, so the first turn arms the feedback
            val script = FakeLlmScript().apply {
                queueText("skip ahead [[stage:done]]")
                queueText("still working")
                queueText("more work")
            }

            withTurnEngine({ scriptedApi(script) }, task = TaskNotes("t", stage = TaskStage.PLANNING)) {
                // when
                engine.turn("go")
                engine.turn("go")
                engine.turn("go")

                // then
                val signal = script.calls[1].messages.firstOrNull { it.role == Role.SYSTEM && "[fsm]" in it.text }
                assertNotNull(signal)
                assertTrue("planning" in signal.text)
                assertTrue("done" in signal.text)
                assertTrue("execution" in signal.text)
                assertTrue(script.calls[2].messages.none { it.role == Role.SYSTEM && "[fsm]" in it.text })
            }
        }

    @Test
    fun `when a legal stage move precedes a turn - then no FSM signal is injected`() = runTest {
        // given — PLANNING → EXECUTION is legal, so nothing is armed
        val script = FakeLlmScript().apply {
            queueText("plan ready [[stage:execution]]")
            queueText("executing")
        }

        withTurnEngine({ scriptedApi(script) }, task = TaskNotes("t", stage = TaskStage.PLANNING)) {
            // when
            engine.turn("go")
            engine.turn("go")

            // then
            assertTrue(script.calls[1].messages.none { it.role == Role.SYSTEM && "[fsm]" in it.text })
        }
    }

    //endregion

    //region подсказка при застревании

    @Test
    fun `when a stage stalls twice with the hint armed - then the next turn is nudged toward the next stage`() =
        runTest {
            // given — VALIDATION, and the model keeps signalling the CURRENT stage (no move)
            val script = FakeLlmScript().apply {
                queueText("still checking [[stage:validation]]") // NO_MOVE — streak 1
                queueText("still checking [[stage:validation]]") // NO_MOVE — streak 2, arms the nudge
                queueText("done now")
            }

            withTurnEngine(
                { scriptedApi(script) },
                task = TaskNotes("t", stage = TaskStage.VALIDATION),
                stallHint = true,
            ) {
                // when
                engine.turn("go")
                engine.turn("go")
                engine.turn("go")

                // then — the no-move note lands at once (turn 2, one re-signal is enough), while the
                // nudge waits for the streak (turn 3) and names the NEXT stage, not validation-only
                assertTrue(script.calls[0].messages.none { it.role == Role.SYSTEM && "[fsm]" in it.text })
                assertTrue(script.calls[1].messages.any { it.role == Role.SYSTEM && "[fsm] no move:" in it.text })
                assertTrue(script.calls[1].messages.none { it.role == Role.SYSTEM && "[fsm] stalled:" in it.text })
                val nudge =
                    script.calls[2].messages.firstOrNull { it.role == Role.SYSTEM && "[fsm] stalled:" in it.text }
                assertNotNull(nudge)
                assertTrue("validation" in nudge.text)
                assertTrue("done" in nudge.text)
            }
        }

    @Test
    fun `when a stage stalls but the hint is not armed - then no nudge is ever injected`() = runTest {
        // given — same stall, but stallHint left at its default (off) — the production-parity path
        val script = FakeLlmScript().apply {
            queueText("still checking [[stage:validation]]")
            queueText("still checking [[stage:validation]]")
            queueText("still checking [[stage:validation]]")
        }

        withTurnEngine({ scriptedApi(script) }, task = TaskNotes("t", stage = TaskStage.VALIDATION)) {
            // when
            engine.turn("go")
            engine.turn("go")
            engine.turn("go")

            // then — the flag gates the nudge only. The no-move note is not gated: it is the
            // engine telling the model its marker did nothing, which is true either way.
            assertTrue(
                script.calls.all { call ->
                    call.messages.none { it.role == Role.SYSTEM && "[fsm] stalled:" in it.text }
                },
            )
            assertTrue(script.calls[1].messages.any { it.role == Role.SYSTEM && "[fsm] no move:" in it.text })
        }
    }

    @Test
    fun `when a real stage move breaks the stall run - then the streak resets and no nudge fires`() = runTest {
        // given — a stall, then a real move (resets the streak), then another lone stall: never two in a row
        val script = FakeLlmScript().apply {
            queueText("still checking [[stage:validation]]") // NO_MOVE — streak 1
            queueText("back to it [[stage:execution]]")      // Advanced VALIDATION→EXECUTION — streak resets
            queueText("still working [[stage:execution]]")   // NO_MOVE — streak 1 again
            queueText("more")
        }

        withTurnEngine(
            { scriptedApi(script) },
            task = TaskNotes("t", stage = TaskStage.VALIDATION),
            stallHint = true,
        ) {
            // when
            repeat(4) { engine.turn("go") }

            // then — the streak never reached the limit twice in a row, so nothing is ever nudged
            // (the lone re-signals still draw their own no-move note; that is not the nudge)
            assertTrue(
                script.calls.all { call ->
                    call.messages.none { it.role == Role.SYSTEM && "[fsm] stalled:" in it.text }
                },
            )
        }
    }

    //endregion

    //region маршрутизация и судья

    @Test
    fun `when the active stage matches a routed agent - then that agent answers`() = runTest {
        // given
        val fallbackScript = FakeLlmScript().apply { queueText("fb") }
        val plannerScript = FakeLlmScript().apply { queueText("planned") }
        val planner = routedAgent(TaskStage.PLANNING, TaskStage.EXECUTION, scriptedApi(plannerScript), "planner")

        withTurnEngine(
            { scriptedApi(fallbackScript) },
            task = TaskNotes("t", stage = TaskStage.PLANNING),
            routedAgents = listOf(planner),
        ) {
            // when
            val result = engine.turn("hi")

            // then
            result as TurnResult.Ok
            assertEquals("planned", result.reply)
            assertEquals("planner", result.profileName)
            assertEquals(1, plannerScript.calls.size)
            assertEquals(0, fallbackScript.calls.size)
        }
    }

    @Test
    fun `when a judge runs - then it gets the user message the stage and the shape sections`() = runTest {
        // given
        val planner = routedAgent(
            TaskStage.PLANNING,
            TaskStage.EXECUTION,
            scriptedApi(FakeLlmScript().apply { queueText("planned") }),
            "planner",
        )
        val judge = RecordingJudge()

        withTurnEngine(
            { scriptedApi(FakeLlmScript()) },
            task = TaskNotes("t", stage = TaskStage.PLANNING),
            routedAgents = listOf(planner),
            routedJudges = listOf(judge.routed()),
        ) {
            // given — a profile whose four sections are all populated (read fresh every turn)
            memStore.addNamedProfileItem("planner", ProfileSection.CONSTRAINTS, "no guessing")
            memStore.addNamedProfileItem("planner", ProfileSection.FORMAT, "name the doc file")
            memStore.addNamedProfileItem("planner", ProfileSection.STYLE, "be brief")
            memStore.addNamedProfileItem("planner", ProfileSection.CONTEXT, "call find_user first")

            // when
            engine.turn("my server is down")

            // then — the whole turn reaches the judge, except the section that says how to work
            val seen = judge.seen
            assertEquals("my server is down", seen?.userMessage)
            assertEquals(TaskStage.PLANNING, seen?.stage)
            assertEquals(listOf("no guessing"), seen?.constraints)
            assertEquals(listOf("name the doc file"), seen?.format)
            assertEquals(listOf("be brief"), seen?.style)
            val everythingTheJudgeGot = listOf(seen?.constraints, seen?.format, seen?.style).flatMap { it.orEmpty() }
            assertTrue(
                everythingTheJudgeGot.none { it.contains("find_user") },
                "the profile context section must never reach the judge — it says how to work, not what is forbidden",
            )
        }
    }

    //endregion
}

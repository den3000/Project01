package ru.den.writes.code.agenticHub.features.lifecycle.session.inline

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.lifecycle.session.ProfileItem
import ru.den.writes.code.agenticHub.features.lifecycle.session.RecordingJudge
import ru.den.writes.code.agenticHub.features.lifecycle.session.routedAgent
import ru.den.writes.code.agenticHub.features.lifecycle.session.runTurnEngineWith
import ru.den.writes.code.agenticHub.features.lifecycle.session.scriptedApi
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
 * Every test drives the engine through the same `runTurnEngineWith` the live stand uses
 * (`TurnEngineFixture.kt`), differing only in the scripted [LlmApi] and how many turns to
 * feed — which is what keeps both suites pointed at the same engine wiring.
 *
 * What is here is what THIS engine does: the wording it puts on the wire, the private streak
 * counter behind its nudge, its routing. What any engine must do — the reply, the history,
 * where the task ends up — moved to `TurnEngineConformanceTest`, which runs it on every
 * implementation instead of on whichever one this file happened to be written against.
 */
class InlineFsmTurnEngineTest {

    //region ход и персист

    @Test
    fun `when the reply is empty with no usage - then Failed with that reason`() = runTest {
        // given
        val api = FakeLlmScript()
            .apply { queue(LlmResult(text = null)) }
            .let { scriptedApi(it) }

        // when
        val run = runTurnEngineWith({ api })

        // then
        assertEquals(TurnResult.Failed("empty response with no usage"), run.results.single())
    }

    //endregion

    //region обратная связь FSM на следующем ходу

    @Test
    fun `when a re-signalled stage precedes a turn - then the next turn's wire carries the no-move note once`() =
        runTest {
            // given
            val script = FakeLlmScript().apply {
                queueText("still checking [[stage:validation]]")
                queueText("wrapping up")
                queueText("more")
            }
            val api = scriptedApi(script)

            // when
            runTurnEngineWith({ api }, task = TaskNotes("t", stage = TaskStage.VALIDATION), turns = 3)

            // then
            val note = script.calls[1].messages.firstOrNull { it.role == Role.SYSTEM && "[fsm] no move:" in it.text }
            assertNotNull(note)
            assertTrue("[[stage:validation]]" in note.text)
            assertTrue("done" in note.text)
            assertTrue("execution" in note.text)
            assertTrue(script.calls[2].messages.none { it.role == Role.SYSTEM && "[fsm]" in it.text })
        }

    @Test
    fun `when a rejected stage move precedes a turn - then the next turn's wire carries the FSM signal once`() =
        runTest {
            // given
            val script = FakeLlmScript().apply {
                queueText("skip ahead [[stage:done]]")
                queueText("still working")
                queueText("more work")
            }
            val api = scriptedApi(script)

            // when
            runTurnEngineWith({ api }, task = TaskNotes("t", stage = TaskStage.PLANNING), turns = 3)

            // then
            val signal = script.calls[1].messages.firstOrNull { it.role == Role.SYSTEM && "[fsm]" in it.text }
            assertNotNull(signal)
            assertTrue("planning" in signal.text)
            assertTrue("done" in signal.text)
            assertTrue("execution" in signal.text)
            assertTrue(script.calls[2].messages.none { it.role == Role.SYSTEM && "[fsm]" in it.text })
        }

    @Test
    fun `when a legal stage move precedes a turn - then no FSM signal is injected`() = runTest {
        // given
        val script = FakeLlmScript().apply {
            queueText("plan ready [[stage:execution]]")
            queueText("executing")
        }
        val api = scriptedApi(script)

        // when
        runTurnEngineWith({ api }, task = TaskNotes("t", stage = TaskStage.PLANNING), turns = 2)

        // then
        assertTrue(script.calls[1].messages.none { it.role == Role.SYSTEM && "[fsm]" in it.text })
    }

    //endregion

    //region подсказка при застревании

    @Test
    fun `when a stage stalls twice with the hint armed - then the next turn is nudged toward the next stage`() =
        runTest {
            // given
            val script = FakeLlmScript().apply {
                queueText("still checking [[stage:validation]]")
                queueText("still checking [[stage:validation]]")
                queueText("done now")
            }
            val api = scriptedApi(script)

            // when
            runTurnEngineWith(
                { api },
                task = TaskNotes("t", stage = TaskStage.VALIDATION),
                turns = 3,
                stallHint = true,
            )

            // then
            assertTrue(script.calls[0].messages.none { it.role == Role.SYSTEM && "[fsm]" in it.text })
            assertTrue(script.calls[1].messages.any { it.role == Role.SYSTEM && "[fsm] no move:" in it.text })
            assertTrue(script.calls[1].messages.none { it.role == Role.SYSTEM && "[fsm] stalled:" in it.text })
            val nudge = script.calls[2].messages.firstOrNull { it.role == Role.SYSTEM && "[fsm] stalled:" in it.text }
            assertNotNull(nudge)
            assertTrue("validation" in nudge.text)
            assertTrue("done" in nudge.text)
        }

    @Test
    fun `when a stage stalls but the hint is not armed - then no nudge is ever injected`() = runTest {
        // given
        val script = FakeLlmScript().apply {
            queueText("still checking [[stage:validation]]")
            queueText("still checking [[stage:validation]]")
            queueText("still checking [[stage:validation]]")
        }
        val api = scriptedApi(script)

        // when
        runTurnEngineWith({ api }, task = TaskNotes("t", stage = TaskStage.VALIDATION), turns = 3)

        // then
        assertTrue(
            script.calls.all { call ->
                call.messages.none { it.role == Role.SYSTEM && "[fsm] stalled:" in it.text }
            },
        )
        assertTrue(script.calls[1].messages.any { it.role == Role.SYSTEM && "[fsm] no move:" in it.text })
    }

    @Test
    fun `when a real stage move breaks the stall run - then the streak resets and no nudge fires`() = runTest {
        // given
        val script = FakeLlmScript().apply {
            queueText("still checking [[stage:validation]]")
            queueText("back to it [[stage:execution]]")
            queueText("still working [[stage:execution]]")
            queueText("more")
        }
        val api = scriptedApi(script)

        // when
        runTurnEngineWith(
            { api },
            task = TaskNotes("t", stage = TaskStage.VALIDATION),
            turns = 4,
            stallHint = true,
        )

        // then
        assertTrue(
            script.calls.all { call ->
                call.messages.none { it.role == Role.SYSTEM && "[fsm] stalled:" in it.text }
            },
        )
    }

    @Test
    fun `when the task is paused - then a stalled stage is never nudged`() = runTest {
        // given
        val script = FakeLlmScript().apply {
            queueText("still checking [[stage:validation]]")
            queueText("still checking [[stage:validation]]")
            queueText("still checking [[stage:validation]]")
        }
        val api = scriptedApi(script)

        // when
        runTurnEngineWith(
            { api },
            task = TaskNotes("t", stage = TaskStage.VALIDATION, paused = true),
            turns = 3,
            stallHint = true,
        )

        // then
        assertEquals(3, script.calls.size)
        assertTrue(
            script.calls.all { call -> call.messages.none { it.role == Role.SYSTEM && "[fsm]" in it.text } },
        )
    }

    @Test
    fun `when the task is already done - then the terminal stage is never nudged`() = runTest {
        // given
        val script = FakeLlmScript().apply {
            queueText("nothing left to do")
            queueText("nothing left to do")
            queueText("nothing left to do")
        }
        val api = scriptedApi(script)

        // when
        runTurnEngineWith(
            { api },
            task = TaskNotes("t", stage = TaskStage.DONE),
            turns = 3,
            stallHint = true,
            stopAtDone = false,
        )

        // then
        assertEquals(3, script.calls.size)
        assertTrue(
            script.calls.all { call -> call.messages.none { it.role == Role.SYSTEM && "[fsm]" in it.text } },
        )
    }

    @Test
    fun `when there is no task at all - then no-move turns never nudge`() = runTest {
        // given
        val script = FakeLlmScript().apply {
            queueText("just talking")
            queueText("still talking")
            queueText("talking on")
        }
        val api = scriptedApi(script)

        // when
        runTurnEngineWith({ api }, turns = 3, stallHint = true)

        // then
        assertEquals(3, script.calls.size)
        assertTrue(
            script.calls.all { call -> call.messages.none { it.role == Role.SYSTEM && "[fsm]" in it.text } },
        )
    }

    //endregion

    //region маршрутизация и судья

    @Test
    fun `when the active stage matches a routed agent - then that agent answers`() = runTest {
        // given
        val fallbackScript = FakeLlmScript().apply { queueText("fb") }
        val plannerScript = FakeLlmScript().apply { queueText("planned") }
        val planner = routedAgent(TaskStage.PLANNING, TaskStage.EXECUTION, scriptedApi(plannerScript), "planner")
        val api = scriptedApi(fallbackScript)

        // when
        val run = runTurnEngineWith(
            { api },
            task = TaskNotes("t", stage = TaskStage.PLANNING),
            routedAgents = listOf(planner),
        )

        // then
        assertEquals("planned", run.ok().reply)
        assertEquals("planner", run.ok().profileName)
        assertEquals(1, plannerScript.calls.size)
        assertEquals(0, fallbackScript.calls.size)
    }

    @Test
    fun `when a judge runs - then it gets the user message the stage and the shape sections`() = runTest {
        // given
        val planner = routedAgent(
            TaskStage.PLANNING,
            TaskStage.EXECUTION,
            FakeLlmScript().apply { queueText("planned") }.let { scriptedApi(it) },
            "planner",
        )
        val judge = RecordingJudge()
        val api = scriptedApi(FakeLlmScript())

        // when
        runTurnEngineWith(
            { api },
            task = TaskNotes("t", stage = TaskStage.PLANNING),
            prompt = "my server is down",
            routedAgents = listOf(planner),
            routedJudges = listOf(judge.routed()),
            profileItems = listOf(
                ProfileItem("planner", ProfileSection.CONSTRAINTS, "no guessing"),
                ProfileItem("planner", ProfileSection.FORMAT, "name the doc file"),
                ProfileItem("planner", ProfileSection.STYLE, "be brief"),
                ProfileItem("planner", ProfileSection.CONTEXT, "call find_user first"),
            ),
        )

        // then
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

    //endregion
}

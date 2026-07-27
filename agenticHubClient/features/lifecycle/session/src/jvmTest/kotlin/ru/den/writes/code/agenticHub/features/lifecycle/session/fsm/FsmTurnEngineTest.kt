package ru.den.writes.code.agenticHub.features.lifecycle.session.fsm

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.agent.invariant.InvariantVerdict
import ru.den.writes.code.agenticHub.features.agent.invariant.InvariantViolation
import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.Task
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachine
import ru.den.writes.code.agenticHub.features.fsm.UpdateDecision
import ru.den.writes.code.agenticHub.features.fsm.UpdateReason
import ru.den.writes.code.agenticHub.features.lifecycle.session.FSM_ENGINE
import ru.den.writes.code.agenticHub.features.lifecycle.session.RecordingJudge
import ru.den.writes.code.agenticHub.features.lifecycle.session.scriptedApi
import ru.den.writes.code.agenticHub.features.lifecycle.session.withTurnEngine
import ru.den.writes.code.agenticHub.features.llm.FakeLlmScript
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import ru.den.writes.code.agenticHub.platform.database.DEFAULT_BRANCH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the engine does with a turn once the answer is in: hand it to the FSM and carry out
 * what comes back. Nothing here asserts what the FSM decided — that is `features:fsm`'s, and
 * repeating it here would only mean two places to change when a rule moves.
 *
 * So the machine is substituted: it records what it was asked and answers whatever the test
 * needs. Half the cases check the question (every way a turn can end reaches the right
 * [ru.den.writes.code.agenticHub.features.fsm.UpdateReason]), half check that the answer is applied (the task is written down, a restart
 * empties the wire, a charged reason reaches the model next turn).
 */
class FsmTurnEngineTest {

    @Test
    fun `when the reply names a stage - then the machine is asked about that stage`() = runTest {
        // given
        val machine = RecordingMachine { decision(it) }
        val api = scriptedApi(FakeLlmScript().apply { queueText("plan ready [[stage:execution]]") })

        // when
        withTurnEngine({ api }, engineUnderTest = FSM_ENGINE, task = task(), machine = machine) {
            engine.turn("one")
        }

        // then
        assertEquals(
            listOf<UpdateReason>(UpdateReason.StageProposed(Stage.EXECUTION)),
            machine.asked
        )
    }

    @Test
    fun `when the reply names no stage - then the machine is told nothing was proposed`() =
        runTest {
            // given
            val machine = RecordingMachine { decision(it) }
            val api = scriptedApi(FakeLlmScript().apply { queueText("thinking out loud") })

            // when
            withTurnEngine(
                { api },
                engineUnderTest = FSM_ENGINE,
                task = task(),
                machine = machine
            ) {
                engine.turn("one")
            }

            // then
            assertEquals(listOf<UpdateReason>(UpdateReason.NoStageProposed), machine.asked)
        }

    @Test
    fun `when the judge withdraws the reply - then the machine is told the answer was blocked`() =
        runTest {
            // given
            val machine = RecordingMachine { decision(it) }
            val judge = RecordingJudge(
                InvariantVerdict(
                    passed = false,
                    violations = listOf(InvariantViolation("r1", "nope"))
                ),
            )
            val api = scriptedApi(FakeLlmScript().apply {
                queueText("breach [[stage:execution]]"); queueText("breach again")
            })

            // when
            withTurnEngine(
                { api },
                engineUnderTest = FSM_ENGINE,
                task = task(),
                routedJudges = listOf(judge.routed()),
                machine = machine,
            ) {
                engine.turn("one")
            }

            // then — the stage the model asked for is never looked at; the turn is simply spent
            assertEquals(listOf<UpdateReason>(UpdateReason.JudgeBlocked), machine.asked)
        }

    @Test
    fun `when the provider cannot be reached - then the machine is told the transport failed`() =
        runTest {
            // given
            val machine = RecordingMachine { decision(it) }
            val api = scriptedApi(FakeLlmScript())

            // when
            withTurnEngine(
                { api },
                engineUnderTest = FSM_ENGINE,
                task = task(),
                machine = machine
            ) {
                engine.turn("one")
            }

            // then
            assertEquals(listOf<UpdateReason>(UpdateReason.TransportFailed), machine.asked)
        }

    @Test
    fun `when there is no active task - then the machine is not asked at all`() = runTest {
        // given
        val machine = RecordingMachine { decision(it) }
        val api =
            scriptedApi(FakeLlmScript().apply { queueText("just talking [[stage:execution]]") })

        // when
        withTurnEngine({ api }, engineUnderTest = FSM_ENGINE, task = null, machine = machine) {
            engine.turn("one")
        }

        // then
        assertTrue(machine.asked.isEmpty(), "the machine was asked ${machine.asked}")
    }

    @Test
    fun `when the machine returns a task - then that task is what gets stored`() = runTest {
        // given — a decision nothing about this turn would produce, so the file can only match it
        val decided = Task(
            taskId = TASK_ID,
            stage = Stage.CLARIFICATION,
            deepestStage = Stage.VALIDATION,
            stageRetryState = RetryState(attempt = 7, max = RetryState.STAGE_MAX),
        )
        val machine = RecordingMachine { decision(decided) }
        val api = scriptedApi(FakeLlmScript().apply { queueText("moving on [[stage:validation]]") })

        // when
        withTurnEngine({ api }, engineUnderTest = FSM_ENGINE, task = task(), machine = machine) {
            engine.turn("one")

            // then
            val stored = checkNotNull(memStore.loadTask(TASK_ID))
            assertEquals(TaskStage.CLARIFICATION, stored.stage)
            assertEquals(TaskStage.VALIDATION, stored.deepestStage)
            assertEquals(7, stored.stageRetriesSpent)
        }
    }

    @Test
    fun `when the machine restarts the task - then the next turn talks on a fresh branch`() =
        runTest {
            // given
            val restarted = Task(
                taskId = TASK_ID,
                taskRetryState = RetryState(attempt = 1, max = RetryState.TASK_MAX)
            )
            val machine = RecordingMachine {
                decision(
                    restarted,
                    retryOutcome = RetryOutcome.Restarted(restarted)
                )
            }
            val script = FakeLlmScript().apply {
                queueText("giving up here")
                queueText("starting over")
            }
            val api = scriptedApi(script)

            // when
            withTurnEngine(
                { api },
                engineUnderTest = FSM_ENGINE,
                task = task(),
                machine = machine
            ) {
                engine.turn("one")
                engine.turn("two")

                // then — the branch is new, and the failed attempt is not on the wire any more
                assertEquals(listOf(DEFAULT_BRANCH, "${DEFAULT_BRANCH}-attempt-2"), branches())
                assertTrue(script.calls[1].messages.none { "giving up here" in it.text })
            }
        }

    @Test
    fun `when the machine charges a reason - then the next turn opens with it`() = runTest {
        // given
        val machine = RecordingMachine { task ->
            decision(
                task,
                retryOutcome = RetryOutcome.Retried(task),
                retryReason = RetryReason.NO_MARKER,
                allowedNext = setOf(Stage.VALIDATION),
            )
        }
        val script = FakeLlmScript().apply {
            queueText("no marker here")
            queueText("still nothing")
        }
        val api = scriptedApi(script)

        // when
        withTurnEngine({ api }, engineUnderTest = FSM_ENGINE, task = task(), machine = machine) {
            engine.turn("one")
            engine.turn("two")
        }

        // then — said once, on the turn after the charge, and quoting the stages it was given
        val note = script.calls[1].messages.single { it.role == Role.SYSTEM && "[fsm]" in it.text }
        assertTrue(TaskStage.VALIDATION.keyword in note.text, note.text)
        assertTrue(script.calls[0].messages.none { it.role == Role.SYSTEM && "[fsm]" in it.text })
    }

    /**
     * A machine that answers instead of deciding: it keeps every [UpdateReason] it was handed
     * and returns whatever [answer] says. No default answer on purpose — what the machine
     * replies is half of what each test is about, so every one of them states it.
     */
    private class RecordingMachine(
        private val answer: RecordingMachine.(Task) -> UpdateDecision,
    ) : TaskStateMachine {
        val asked = mutableListOf<UpdateReason>()

        override fun update(task: Task, reason: UpdateReason): UpdateDecision {
            asked += reason
            return answer(task)
        }

        /** What to hand back; `advance` stays null — what the view renders is not the point here. */
        fun decision(
            task: Task,
            retryOutcome: RetryOutcome? = null,
            retryReason: RetryReason? = null,
            allowedNext: Set<Stage> = emptySet(),
        ) = UpdateDecision(
            task = task,
            advance = null,
            retryOutcome = retryOutcome,
            retryReason = retryReason,
            allowedNext = allowedNext,
        )
    }

    private fun task() =
        TaskNotes(taskId = TASK_ID, stage = TaskStage.EXECUTION, deepestStage = TaskStage.EXECUTION)

    private companion object {
        const val TASK_ID = "t"
    }
}
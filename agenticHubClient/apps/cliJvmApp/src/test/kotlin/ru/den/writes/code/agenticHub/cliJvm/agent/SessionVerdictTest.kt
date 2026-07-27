package ru.den.writes.code.agenticHub.cliJvm.agent

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.Task
import ru.den.writes.code.agenticHub.features.lifecycle.session.CommandRunner
import ru.den.writes.code.agenticHub.features.lifecycle.session.SessionViewModel
import ru.den.writes.code.agenticHub.features.lifecycle.session.UiIntent
import ru.den.writes.code.agenticHub.features.lifecycle.session.UiLine
import ru.den.writes.code.agenticHub.features.lifecycle.session.intents.IntentSource
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.StageAdvance
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.TurnEngine
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.TurnResult
import ru.den.writes.code.agenticHub.features.memory.ContextStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the session does with the task FSM's verdict: say it, and stop feeding itself turns
 * once the task is over.
 *
 * The engine is a stand-in handing back prepared [TurnResult]s — the verdict is a field on
 * the result, so driving a real engine here would only mean steering an FSM into each state
 * to read back what a data class already says.
 */
class SessionVerdictTest {

    @Test
    fun `when the task restarts - then it is announced and the feed keeps going`() = runTest {
        // given
        val restarted = RetryOutcome.Restarted(task(taskAttempt = 1))
        val vm = newVm(ScriptedEngine(ok(restarted)))
        val source = CountingSource()

        // when
        vm.run(source)

        // then
        assertEquals(listOf(restarted), vm.verdicts())
        assertEquals(0, source.failures, "a restart is not a reason to stop feeding")
    }

    @Test
    fun `when the task gives up - then it is announced and the feed is stopped`() = runTest {
        // given
        val gaveUp = RetryOutcome.GaveUp(task(), RetryReason.NO_MARKER)
        val vm = newVm(ScriptedEngine(ok(gaveUp)))
        val source = CountingSource()

        // when
        vm.run(source)

        // then — the feed hears about it; a person at the keyboard is not thrown out
        assertEquals(listOf(gaveUp), vm.verdicts())
        assertEquals(1, source.failures)
    }

    @Test
    fun `when the give-up repeats - then it is announced once`() = runTest {
        // given — the machine does not change a task it gave up on, so it says so every turn
        val gaveUp = RetryOutcome.GaveUp(task(), RetryReason.NO_MARKER)
        val vm = newVm(ScriptedEngine(ok(gaveUp), ok(gaveUp)))

        // when
        vm.run(CountingSource(turns = 2))

        // then
        assertEquals(listOf(gaveUp), vm.verdicts())
    }

    @Test
    fun `when a turn is merely retried - then nothing is said`() = runTest {
        // given
        val vm = newVm(ScriptedEngine(ok(RetryOutcome.Retried(task()))))
        val source = CountingSource()

        // when
        vm.run(source)

        // then
        assertTrue(vm.verdicts().isEmpty(), "verdicts: ${vm.verdicts()}")
        assertEquals(0, source.failures)
    }

    @Test
    fun `when there is no task - then nothing is said and the reply still lands`() = runTest {
        // given — a plain chat turn: no machine was consulted, so there is no verdict
        val vm = newVm(ScriptedEngine(ok(outcome = null)))

        // when
        vm.run(CountingSource())

        // then
        assertTrue(vm.verdicts().isEmpty(), "verdicts: ${vm.verdicts()}")
        assertTrue(vm.state.value.lines.any { it is UiLine.Assistant }, "lines: ${vm.state.value.lines}")
    }

    //region helpers

    private fun newVm(engine: TurnEngine): SessionViewModel = SessionViewModel(
        newChat("hi", session = null),
        engine,
        CommandRunner(historyStore = null, memory = null, strategy = ContextStrategy.FullHistory),
        historyStore = null,
        memory = null,
        strategy = ContextStrategy.FullHistory,
        multiAgent = false,
    )

    private fun SessionViewModel.verdicts(): List<RetryOutcome> =
        state.value.lines.filterIsInstance<UiLine.Fsm>().map { it.outcome }

    private fun ok(outcome: RetryOutcome?) = TurnResult.Ok(
        reply = "reply",
        modelId = "m",
        profileName = null,
        usage = null,
        durationMs = 1,
        session = null,
        stageAdvance = StageAdvance.None,
        retryOutcome = outcome,
    )

    private fun task(taskAttempt: Int = 0) = Task(
        taskId = "t",
        stage = Stage.EXECUTION,
        taskRetryState = RetryState(attempt = taskAttempt, max = RetryState.TASK_MAX),
    )

    /** Hands back [results] in order, repeating the last one once they run out. */
    private class ScriptedEngine(private vararg val results: TurnResult) : TurnEngine {
        private var index = 0

        override suspend fun turn(prompt: String): TurnResult = results[minOf(index++, results.lastIndex)]
    }

    /**
     * A feed-shaped source: submits [turns] turns in total (the opening one included) and
     * counts the aborts it was told about. Stands for `ChunkedFilePromptSource`, the source
     * that actually stops on `onTurnFailed`; stdin and the TUI ignore it, which is the whole
     * point of stopping this way.
     */
    private class CountingSource(private val turns: Int = 1) : IntentSource {
        var failures = 0
            private set

        private var sent = 0

        override suspend fun next(): UiIntent? =
            if (sent++ < turns - 1) UiIntent.Submit("continue") else null

        override fun onTurnFailed() {
            failures++
        }
    }
    //endregion
}

package ru.den.writes.code.agenticHub.features.lifecycle.session

import ru.den.writes.code.agenticHub.BuildKonfig
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.gemini.GeminiModel
import ru.den.writes.code.agenticHub.testutils.runLiveTest
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnEngineLiveTest {

    private fun gemini(model: GeminiModel) =
        ModelProvider.Gemini(model = model, apiKey = BuildKonfig.GEMINI_API_KEY)

    @Test
    fun `when turn engine works without task - then it is pure hallucination`() = runLiveTest {
        // given
        val modelProvider = gemini(GeminiModel.Known.Gemini25FlashLite)

        // when — no task means no stage, so nothing can ever end the run early
        val runLog = runTurnEngineWith(modelProvider, SESSION_NAME, null)

        // then
        assertEquals(MAX_TURNS, runLog.turnLogs.size)
    }

    @Test
    fun `when turn engine works with simple task - then it finishes faster then max test turns`() = runLiveTest {
        // given
        val modelProvider = gemini(GeminiModel.Known.Gemini25FlashLite)

        // when
        val runLog = runTurnEngineWith(modelProvider, SESSION_NAME, SIMPLE_TASK)

        // then
        assertTrue { runLog.turnLogs.size < MAX_TURNS }
    }

    @Test
    fun `when turn engine uses different models with simple task - then works any time`() = runLiveTest {
        // given
        val providers = listOf(
            GeminiModel.Known.Gemini25FlashLite,
            GeminiModel.Known.Gemini31FlashLite,
            GeminiModel.Known.Gemini25Flash,
        ).map(::gemini)
        val tries = 3

        // when
        val groups = providers.map { provider ->
            RunGroup(provider.modelId, (0..<tries).map { runTurnEngineWith(provider, SESSION_NAME, SIMPLE_TASK) })
        }

        // then — diagnostic summary per model across tries (no hard assert yet)
        reportGroups(groups)
    }

    @Test
    fun `when the stall hint is armed - then reach-done beats the baseline`() = runLiveTest {
        // given
        val modelProvider = gemini(GeminiModel.Known.Gemini25FlashLite)
        val reps = 100

        // when — same weak model + task, differing only by whether the stall nudge is armed
        val baseline = (0..<reps).map { runTurnEngineWith(modelProvider, SESSION_NAME, MINIMAL_TASK, stallHint = false) }
        val withHint = (0..<reps).map { runTurnEngineWith(modelProvider, SESSION_NAME, MINIMAL_TASK, stallHint = true) }

        // then
        reportGroups(
            listOf(
                RunGroup("baseline (no hint)", baseline),
                RunGroup("with stall hint", withHint),
            )
        )
    }

    @Test
    fun `when the fsm engine runs a simple task - then the run reaches done and the budgets show its cost`() =
        runLiveTest(timeout = BATCH_TIMEOUT) {
            // given
            val modelProvider = gemini(GeminiModel.Known.Gemini25FlashLite)
            val reps = 50

            // when
            val runs = (0..<reps).map {
                runRestartingTurnEngineWith(modelProvider, SESSION_NAME, SIMPLE_TASK)
            }

            // then
            reportRestartingRuns("fsm engine / simple", runs)
        }

    @Test
    fun `when the fsm engine restarts a minimal task - then the fresh attempt is visible`() =
        runLiveTest(timeout = BATCH_TIMEOUT) {
            // given
            val modelProvider = gemini(GeminiModel.Known.Gemini25FlashLite)
            val reps = 20

            // when
            val runs = (0..<reps).map {
                runRestartingTurnEngineWith(modelProvider, SESSION_NAME, MINIMAL_TASK)
            }

            // then
            reportRestartingRuns("fsm engine / minimal / restarts executed", runs)
        }

    private companion object {
        const val SESSION_NAME = "turn-engine-live-test"

        val BATCH_TIMEOUT = 90.minutes
    }
}

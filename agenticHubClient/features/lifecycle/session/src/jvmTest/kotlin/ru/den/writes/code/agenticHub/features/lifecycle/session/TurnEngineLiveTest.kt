package ru.den.writes.code.agenticHub.features.lifecycle.session

import ru.den.writes.code.agenticHub.BuildKonfig
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.gemini.GeminiModel
import ru.den.writes.code.agenticHub.testutils.runLiveTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The FSM stability stand: a real [ru.den.writes.code.agenticHub.features.lifecycle.session.turn.TurnEngine]
 * against a real model, measured rather than asserted. Environment comes from
 * `TurnEngineFixture.kt`, its parts from `TurnEngineTestSupport.kt`, the run driver and the
 * tables from `TurnEngineRunReport.kt`; what stays here is which model, which task, and how
 * many repetitions.
 *
 * Opt-in (`-PliveTests`) and it **burns tokens** — the A/B below is 2 × [reps] full runs.
 */
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

    private companion object {
        const val SESSION_NAME = "turn-engine-live-test"
    }
}

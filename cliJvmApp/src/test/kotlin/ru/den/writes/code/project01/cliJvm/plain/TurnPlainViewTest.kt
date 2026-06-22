package ru.den.writes.code.project01.cliJvm.plain

import ru.den.writes.code.project01.cliJvm.SessionStatsSnapshot
import ru.den.writes.code.project01.shared.llm.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-turn footer builder. Pins the byte contract the
 * `agent.PlainViewGoldenTest` can't reach — it runs off-registry, so the
 * priced-cost, context-fill, thoughts-token and context-warning branches are
 * exercised directly here.
 */
class TurnPlainViewTest {

    @Test
    fun `when model is off-registry - then no-pricing cost and no context line`() {
        // given — an unknown model (no pricing entry), single turn, no session
        val view = TurnPlainView(Usage(10, 5, 0, 15), modelId = "no-such-model", durationMs = 0, session = null)

        // when - then
        assertEquals(
            listOf(
                RULE,
                "duration: 0 ms",
                "turn:    prompt=10  output=5  total=15  cost=$? (no pricing)",
                RULE,
            ),
            view.stdout(),
        )
        assertTrue(view.stderr().isEmpty())
    }

    @Test
    fun `when usage has thoughts tokens - then they appear between output and total`() {
        // given
        val view = TurnPlainView(Usage(100, 20, 30, 150), modelId = "no-such-model", durationMs = 0, session = null)

        // when - then
        assertTrue(
            view.stdout().any { it == "turn:    prompt=100  output=20  thoughts=30  total=150  cost=$? (no pricing)" },
            "thoughts token segment: ${view.stdout()}",
        )
    }

    @Test
    fun `when the model is priced - then five-decimal cost and a context-fill line`() {
        // given — a registered free model (priced at 0, so cost is deterministic) with a 131072 window
        val view = TurnPlainView(Usage(10, 5, 0, 15), modelId = PRICED_MODEL, durationMs = 0, session = null)

        // when
        val out = view.stdout()

        // then
        assertTrue(out.any { it == "turn:    prompt=10  output=5  total=15  cost=$0.00000" }, "priced cost: $out")
        assertTrue(out.any { it == "context: 10 / 131072 (0.0%)" }, "context-fill line: $out")
    }

    @Test
    fun `when a session snapshot is present - then a session line is added`() {
        // given
        val session = SessionStatsSnapshot(2, promptTokens = 20, outputTokens = 10, thoughtsTokens = 0, totalTokens = 30, costUsd = 0.0)
        val view = TurnPlainView(Usage(10, 5, 0, 15), modelId = "no-such-model", durationMs = 0, session = session)

        // when - then
        assertTrue(
            view.stdout().any { it == "session: turns=2 prompt=20  output=10  total=30  cost=$? (no pricing)" },
            "session line: ${view.stdout()}",
        )
    }

    @Test
    fun `when the context window is full - then a warning goes to stderr`() {
        // given — prompt at 100% of the priced model's 131072 window
        val view = TurnPlainView(Usage(131072, 0, 0, 131072), modelId = PRICED_MODEL, durationMs = 0, session = null)

        // when - then
        assertEquals(listOf("[warning] context window 100.0% full — next turn may overflow"), view.stderr())
    }

    private companion object {
        val RULE = "<".repeat(72)
        const val PRICED_MODEL = "meta-llama/llama-3.3-70b-instruct:free"
    }
}

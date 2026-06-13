package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.cliJvm.db.MessageEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionStatsTest {

    @Test
    fun `record accumulates tokens and cost across calls`() {
        val stats = SessionStats()
        stats.record(
            Usage(promptTokens = 100, outputTokens = 50, thoughtsTokens = 0, totalTokens = 150),
            costUsd = 0.0001,
        )
        stats.record(
            Usage(promptTokens = 200, outputTokens = 30, thoughtsTokens = 70, totalTokens = 300),
            costUsd = 0.0003,
        )
        assertEquals(2, stats.turns)
        assertEquals(300, stats.totalPromptTokens)
        assertEquals(80, stats.totalOutputTokens)
        assertEquals(70, stats.totalThoughtsTokens)
        assertEquals(450, stats.totalTokens)
        assertEquals(0.0004, stats.totalCostUsd, 1e-12)
    }

    @Test
    fun `seedFrom replays only rows that have full token data`() {
        val stats = SessionStats()
        val rows = listOf(
            // USER row — no tokens, should be skipped.
            entity("USER", text = "hi"),
            // ASSISTANT row with full tokens + known model.
            entity(
                "ASSISTANT", text = "reply",
                modelId = "gemini-2.5-flash-lite",
                prompt = 1000, output = 500, total = 1500,
            ),
            // ASSISTANT row with tokens but unknown model — counts toward
            // tokens, contributes zero cost.
            entity(
                "ASSISTANT", text = "reply2",
                modelId = "no-such-model-id",
                prompt = 200, output = 100, total = 300,
            ),
            // ASSISTANT row with missing totalTokens — has to be skipped
            // since the contract is "all four columns present or none".
            entity("ASSISTANT", text = "reply3", prompt = 50, output = 50, total = null),
        )

        stats.seedFrom(rows, PricingRegistry::lookup)

        assertEquals(2, stats.turns)
        assertEquals(1200, stats.totalPromptTokens)
        assertEquals(600, stats.totalOutputTokens)
        assertEquals(1800, stats.totalTokens)
        // Cost: only the gemini-2.5-flash-lite row contributes. The
        // unknown-model row has tokens but no rate, so cost = 0 for it.
        val expected = (1000 * 0.10 + 500 * 0.40) / 1_000_000.0
        assertEquals(expected, stats.totalCostUsd, 1e-12)
    }

    @Test
    fun `seedFrom on empty list leaves stats at zero`() {
        val stats = SessionStats()
        stats.seedFrom(emptyList(), PricingRegistry::lookup)
        assertEquals(0, stats.turns)
        assertEquals(0, stats.totalTokens)
        assertEquals(0.0, stats.totalCostUsd)
    }

    private fun entity(
        role: String,
        text: String,
        modelId: String? = null,
        prompt: Int? = null,
        output: Int? = null,
        thoughts: Int? = null,
        total: Int? = null,
    ): MessageEntity = MessageEntity(
        sessionId = "test",
        role = role,
        text = text,
        modelId = modelId,
        promptTokens = prompt,
        outputTokens = output,
        thoughtsTokens = thoughts,
        totalTokens = total,
    )
}

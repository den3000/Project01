package ru.den.writes.code.project01.cliJvm

import kotlin.test.Test
import kotlin.test.assertEquals

class ContextStrategyTest {

    private val history = listOf(
        Message(Role.USER, "u1"),
        Message(Role.ASSISTANT, "a1"),
        Message(Role.USER, "u2"),
        Message(Role.ASSISTANT, "a2"),
    )

    @Test
    fun `FullHistory returns the history unchanged`() {
        assertEquals(history, ContextStrategy.FullHistory.planContext(history))
    }

    @Test
    fun `Summary with nothing folded yet delegates to the full history`() {
        val strategy = ContextStrategy.Summary(HistoryCompressor(keepLast = 2, summarizeEvery = 2))
        // No summary set → compressor.planContext returns the input as-is.
        assertEquals(history, strategy.planContext(history))
    }

    @Test
    fun `Summary delegates to the compressor's folded view once hydrated`() {
        val compressor = HistoryCompressor(
            keepLast = 2,
            summarizeEvery = 2,
            initialSummary = "SUM",
            initialCoveredCount = 2,
        )
        val planned = ContextStrategy.Summary(compressor).planContext(history)
        // Leads with the synthetic summary pair, drops the covered prefix.
        assertEquals(Message(Role.USER, HistoryCompressor.SUMMARY_FRAME_PREFIX + "SUM"), planned[0])
        assertEquals(Message(Role.ASSISTANT, HistoryCompressor.ACK_TEXT), planned[1])
        assertEquals(Message(Role.USER, "u2"), planned[2])
        assertEquals(Message(Role.ASSISTANT, "a2"), planned[3])
        assertEquals(4, planned.size)
    }
}

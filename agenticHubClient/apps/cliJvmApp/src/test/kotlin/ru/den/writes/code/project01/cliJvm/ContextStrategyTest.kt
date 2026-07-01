package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.shared.context.HistoryCompressor
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import kotlin.test.Test
import kotlin.test.assertEquals

class ContextStrategyTest {

    // Shared fixture: a four-message u/a/u/a transcript used by every test in
    // this class. Kept as a field (not in @Before) so it's visible at a glance.
    private val fourMessageHistory = listOf(
        Message(Role.USER, "u1"),
        Message(Role.ASSISTANT, "a1"),
        Message(Role.USER, "u2"),
        Message(Role.ASSISTANT, "a2"),
    )

    //region FullHistory

    @Test
    fun `when FullHistory planContext called - then history returned unchanged`() {
        // given
        val history = fourMessageHistory

        // when
        val actual = ContextStrategy.FullHistory.planContext(history)

        // then
        val expected = history
        assertEquals(expected, actual)
    }
    //endregion

    //region Summary

    @Test
    fun `when Summary planContext called without prior fold - then full history returned`() {
        // given
        // No summary set → compressor.planContext returns the input as-is.
        val strategy = ContextStrategy.Summary(HistoryCompressor(keepLast = 2, summarizeEvery = 2))

        // when
        val actual = strategy.planContext(fourMessageHistory)

        // then
        val expected = fourMessageHistory
        assertEquals(expected, actual)
    }

    @Test
    fun `when Summary planContext called with hydrated compressor - then folded view returned`() {
        // given
        val compressor = HistoryCompressor(
            keepLast = 2,
            summarizeEvery = 2,
            initialSummary = "SUM",
            initialCoveredCount = 2,
        )
        val strategy = ContextStrategy.Summary(compressor)

        // when
        val actual = strategy.planContext(fourMessageHistory)

        // then
        // Leads with the synthetic summary pair, drops the covered prefix.
        assertEquals(Message(Role.USER, HistoryCompressor.SUMMARY_FRAME_PREFIX + "SUM"), actual[0])
        assertEquals(Message(Role.ASSISTANT, HistoryCompressor.ACK_TEXT), actual[1])
        assertEquals(Message(Role.USER, "u2"), actual[2])
        assertEquals(Message(Role.ASSISTANT, "a2"), actual[3])
        assertEquals(4, actual.size)
    }
    //endregion

    //region SlidingWindow

    @Test
    fun `when SlidingWindow of even N applied - then last N messages returned`() {
        // given
        val strategy = ContextStrategy.SlidingWindow(2)

        // when
        val actual = strategy.planContext(fourMessageHistory)

        // then
        val expected = listOf(Message(Role.USER, "u2"), Message(Role.ASSISTANT, "a2"))
        assertEquals(expected, actual)
    }

    @Test
    fun `when SlidingWindow of odd N applied - then snapped down to last even pair`() {
        // given
        // 3 → 2: keep the last full pair, not a dangling assistant turn.
        val strategy = ContextStrategy.SlidingWindow(3)

        // when
        val actual = strategy.planContext(fourMessageHistory)

        // then
        val expected = listOf(Message(Role.USER, "u2"), Message(Role.ASSISTANT, "a2"))
        assertEquals(expected, actual)
    }

    @Test
    fun `when SlidingWindow with keepLast 0 - then empty tail returned`() {
        // given
        val strategy = ContextStrategy.SlidingWindow(0)

        // when
        val actual = strategy.planContext(fourMessageHistory)

        // then
        val expected = emptyList<Message>()
        assertEquals(expected, actual)
    }

    @Test
    fun `when SlidingWindow larger than history applied - then whole history returned`() {
        // given
        val strategy = ContextStrategy.SlidingWindow(10)

        // when
        val actual = strategy.planContext(fourMessageHistory)

        // then
        val expected = fourMessageHistory
        assertEquals(expected, actual)
    }
    //endregion
}

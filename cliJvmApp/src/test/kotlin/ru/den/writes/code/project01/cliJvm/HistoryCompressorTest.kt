package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryCompressorTest {

    //region planContext (pure)

    @Test
    fun `when planContext called without summary - then messages returned unchanged`() {
        // given
        val compressor = HistoryCompressor(keepLast = 6, summarizeEvery = 10)
        val messages = buildAlternatingMessages(8)

        // when
        val actual = compressor.planContext(messages)

        // then
        val expected = messages
        assertEquals(expected, actual)
    }

    @Test
    fun `when planContext called with summary - then summary pair injected and covered prefix dropped`() {
        // given
        val compressor = HistoryCompressor(
            keepLast = 4, summarizeEvery = 10,
            initialSummary = "SUM", initialCoveredCount = 4,
        )
        val messages = buildAlternatingMessages(8)

        // when
        val actual = compressor.planContext(messages)

        // then
        val expected = listOf(
            Message(Role.USER, HistoryCompressor.SUMMARY_FRAME_PREFIX + "SUM"),
            Message(Role.ASSISTANT, HistoryCompressor.ACK_TEXT),
        ) + messages.drop(4)
        assertEquals(expected, actual)
    }

    @Test
    fun `when planContext called with everything folded - then only summary pair returned`() {
        // given
        // keepLast = 0 + watermark == size → empty tail.
        val compressor = HistoryCompressor(
            keepLast = 0, summarizeEvery = 10,
            initialSummary = "S", initialCoveredCount = 4,
        )
        val messages = buildAlternatingMessages(4)

        // when
        val actual = compressor.planContext(messages)

        // then
        val expected = listOf(
            Message(Role.USER, HistoryCompressor.SUMMARY_FRAME_PREFIX + "S"),
            Message(Role.ASSISTANT, HistoryCompressor.ACK_TEXT),
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `when planContext output appended with user turn - then roles alternate (Gemini-friendly)`() {
        // given
        val compressor = HistoryCompressor(
            keepLast = 4, summarizeEvery = 10,
            initialSummary = "S", initialCoveredCount = 2,
        )

        // when
        val wire = compressor.planContext(buildAlternatingMessages(6)) + Message(Role.USER, "q")

        // then
        assertEquals(Role.USER, wire.first().role)
        wire.zipWithNext().forEach { (a, b) ->
            assertTrue(a.role != b.role, "roles must alternate, got ${a.role} then ${b.role}")
        }
    }

    @Test
    fun `when watermark exceeds size - then planContext falls back to full history`() {
        // given
        val compressor = HistoryCompressor(
            keepLast = 4, summarizeEvery = 10,
            initialSummary = "S", initialCoveredCount = 100,
        )
        val messages = buildAlternatingMessages(4)

        // when
        val actual = compressor.planContext(messages)

        // then
        val expected = messages
        assertEquals(expected, actual)
    }
    //endregion

    //region maybeCompact

    @Test
    fun `when maybeCompact called below threshold - then null returned and no LLM call made`() = runTest {
        // given
        val fakeApi = FakeLlmApi().apply { queueText("summary") }
        val compressor = HistoryCompressor(keepLast = 6, summarizeEvery = 10)
        val messages = buildAlternatingMessages(8) // foldable = 8 - 6 - 0 = 2 < 10

        // when
        val actual = compressor.maybeCompact(messages, fakeApi)

        // then
        assertNull(actual)
        assertNull(compressor.summaryText)
        assertEquals(0, compressor.coveredCount)
        assertEquals(0, fakeApi.calls.size)
    }

    @Test
    fun `when maybeCompact called above threshold - then oldest range folded and even watermark advanced`() = runTest {
        // given
        val fakeApi = FakeLlmApi().apply { queueText("NEW SUMMARY", promptTokens = 100, outputTokens = 20) }
        val compressor = HistoryCompressor(keepLast = 6, summarizeEvery = 10)
        val messages = buildAlternatingMessages(16) // foldable = 16 - 6 - 0 = 10 → fold [0, 10)

        // when
        val outcome = assertNotNull(compressor.maybeCompact(messages, fakeApi))

        // then
        assertEquals(10, compressor.coveredCount)
        assertEquals(0, compressor.coveredCount % 2)
        assertEquals("NEW SUMMARY", compressor.summaryText)
        assertEquals(10, outcome.newCoveredCount)
        assertEquals(0, outcome.foldedFrom)
        assertEquals(10, outcome.foldedTo)
        assertEquals(100, outcome.usage?.promptTokens)

        // One stateless USER summarization call containing the folded texts.
        assertEquals(1, fakeApi.calls.size)
        val call = fakeApi.calls[0]
        assertEquals(1, call.messages.size)
        assertEquals(Role.USER, call.messages[0].role)
        val prompt = call.messages[0].text
        assertTrue(prompt.contains("u0"), "prompt should contain first folded message")
        assertTrue(prompt.contains("a9"), "prompt should contain last folded message")
        assertEquals(HistoryCompressor.SUMMARY_MAX_TOKENS, call.params.maxTokens)
    }

    @Test
    fun `when fold boundary would be odd - then snapped down to even`() = runTest {
        // given
        // Odd-length history (invariant-violating) exercises the defensive
        // even-snap: covered must stay even regardless.
        val fakeApi = FakeLlmApi().apply { queueText("S") }
        val compressor = HistoryCompressor(keepLast = 4, summarizeEvery = 10)
        val messages = buildAlternatingMessages(15) // size - keepLast = 11 (odd)

        // when
        val outcome = compressor.maybeCompact(messages, fakeApi)

        // then
        assertNotNull(outcome)
        assertEquals(10, compressor.coveredCount) // evenDown(11)
        assertEquals(0, compressor.coveredCount % 2)
    }

    @Test
    fun `when maybeCompact has prior summary - then it is rolled into the new summary`() = runTest {
        // given
        val fakeApi = FakeLlmApi().apply { queueText("UPDATED") }
        val compressor = HistoryCompressor(
            keepLast = 6, summarizeEvery = 10,
            initialSummary = "PRIOR", initialCoveredCount = 4,
        )
        val messages = buildAlternatingMessages(20) // foldable = 20 - 6 - 4 = 10 → fold [4, 14)

        // when
        val outcome = assertNotNull(compressor.maybeCompact(messages, fakeApi))

        // then
        assertEquals(14, compressor.coveredCount)
        assertEquals("UPDATED", outcome.newSummary)
        assertTrue(
            fakeApi.calls[0].messages[0].text.contains("PRIOR"),
            "summarization prompt should carry the prior summary",
        )
    }

    @Test
    fun `when summarizer errors - then maybeCompact returns null and state untouched`() = runTest {
        // given
        val fakeApi = FakeLlmApi() // empty queue → error result
        val compressor = HistoryCompressor(keepLast = 6, summarizeEvery = 10)
        val messages = buildAlternatingMessages(16)

        // when
        val actual = compressor.maybeCompact(messages, fakeApi)

        // then
        assertNull(actual)
        assertNull(compressor.summaryText)
        assertEquals(0, compressor.coveredCount)
        assertEquals(1, fakeApi.calls.size) // the call was attempted but failed
    }

    @Test
    fun `when summarizer reply is blank - then treated as failure`() = runTest {
        // given
        val fakeApi = FakeLlmApi().apply { queue(LlmResult(text = "   ")) }
        val compressor = HistoryCompressor(keepLast = 6, summarizeEvery = 10)
        val messages = buildAlternatingMessages(16)

        // when
        val actual = compressor.maybeCompact(messages, fakeApi)

        // then
        assertNull(actual)
        assertNull(compressor.summaryText)
        assertEquals(0, compressor.coveredCount)
    }

    @Test
    fun `when keepLast is odd - then snapped down to even`() = runTest {
        // given
        val fakeApi = FakeLlmApi().apply { queueText("S") }
        val compressor = HistoryCompressor(keepLast = 5, summarizeEvery = 10) // 5 → 4
        val messages = buildAlternatingMessages(16)

        // when
        val outcome = compressor.maybeCompact(messages, fakeApi)

        // then
        assertNotNull(outcome)
        // keepLast effectively 4 → newCovered = evenDown(16 - 4) = 12
        assertEquals(12, compressor.coveredCount)
    }
    //endregion

    /** Build [n] messages as USER/ASSISTANT pairs (even index = USER, odd = ASSISTANT). */
    private fun buildAlternatingMessages(n: Int): List<Message> = (0 until n).map { i ->
        if (i % 2 == 0) Message(Role.USER, "u$i") else Message(Role.ASSISTANT, "a$i")
    }
}

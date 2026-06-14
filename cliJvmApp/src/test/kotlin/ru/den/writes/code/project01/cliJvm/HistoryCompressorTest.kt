package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryCompressorTest {

    // --- planContext (pure) -----------------------------------------

    @Test
    fun `planContext returns messages unchanged when no summary`() {
        val c = HistoryCompressor(keepLast = 6, summarizeEvery = 10)
        val m = msgs(8)
        assertEquals(m, c.planContext(m))
    }

    @Test
    fun `planContext injects summary pair and drops covered prefix`() {
        val c = HistoryCompressor(
            keepLast = 4, summarizeEvery = 10,
            initialSummary = "SUM", initialCoveredCount = 4,
        )
        val m = msgs(8)
        assertEquals(
            listOf(
                Message(Role.USER, HistoryCompressor.SUMMARY_FRAME_PREFIX + "SUM"),
                Message(Role.ASSISTANT, HistoryCompressor.ACK_TEXT),
            ) + m.drop(4),
            c.planContext(m),
        )
    }

    @Test
    fun `planContext with everything folded yields only the summary pair`() {
        // keepLast = 0 + watermark == size → empty tail.
        val c = HistoryCompressor(
            keepLast = 0, summarizeEvery = 10,
            initialSummary = "S", initialCoveredCount = 4,
        )
        assertEquals(
            listOf(
                Message(Role.USER, HistoryCompressor.SUMMARY_FRAME_PREFIX + "S"),
                Message(Role.ASSISTANT, HistoryCompressor.ACK_TEXT),
            ),
            c.planContext(msgs(4)),
        )
    }

    @Test
    fun `planContext output then current user turn alternates roles for Gemini`() {
        val c = HistoryCompressor(
            keepLast = 4, summarizeEvery = 10,
            initialSummary = "S", initialCoveredCount = 2,
        )
        val wire = c.planContext(msgs(6)) + Message(Role.USER, "q")
        assertEquals(Role.USER, wire.first().role)
        wire.zipWithNext().forEach { (a, b) ->
            assertTrue(a.role != b.role, "roles must alternate, got ${a.role} then ${b.role}")
        }
    }

    @Test
    fun `planContext falls back to full history when watermark exceeds size`() {
        val c = HistoryCompressor(
            keepLast = 4, summarizeEvery = 10,
            initialSummary = "S", initialCoveredCount = 100,
        )
        val m = msgs(4)
        assertEquals(m, c.planContext(m))
    }

    // --- maybeCompact ------------------------------------------------

    @Test
    fun `maybeCompact returns null below threshold and makes no call`() = runTest {
        val fake = FakeLlmApi().apply { queueText("summary") }
        val c = HistoryCompressor(keepLast = 6, summarizeEvery = 10)
        val m = msgs(8) // foldable = 8 - 6 - 0 = 2 < 10

        assertNull(c.maybeCompact(m, fake))
        assertNull(c.summaryText)
        assertEquals(0, c.coveredCount)
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `maybeCompact folds oldest range and advances even watermark`() = runTest {
        val fake = FakeLlmApi().apply { queueText("NEW SUMMARY", promptTokens = 100, outputTokens = 20) }
        val c = HistoryCompressor(keepLast = 6, summarizeEvery = 10)
        val m = msgs(16) // foldable = 16 - 6 - 0 = 10 → fold [0, 10)

        val outcome = assertNotNull(c.maybeCompact(m, fake))

        assertEquals(10, c.coveredCount)
        assertEquals(0, c.coveredCount % 2)
        assertEquals("NEW SUMMARY", c.summaryText)
        assertEquals(10, outcome.newCoveredCount)
        assertEquals(0, outcome.foldedFrom)
        assertEquals(10, outcome.foldedTo)
        assertEquals(100, outcome.usage?.promptTokens)

        // One stateless USER summarization call containing the folded texts.
        assertEquals(1, fake.calls.size)
        val call = fake.calls[0]
        assertEquals(1, call.messages.size)
        assertEquals(Role.USER, call.messages[0].role)
        val prompt = call.messages[0].text
        assertTrue(prompt.contains("u0"), "prompt should contain first folded message")
        assertTrue(prompt.contains("a9"), "prompt should contain last folded message")
        assertEquals(HistoryCompressor.SUMMARY_MAX_TOKENS, call.params.maxTokens)
    }

    @Test
    fun `maybeCompact snaps an odd fold boundary down to even`() = runTest {
        // Odd-length history (invariant-violating) exercises the defensive
        // even-snap: covered must stay even regardless.
        val fake = FakeLlmApi().apply { queueText("S") }
        val c = HistoryCompressor(keepLast = 4, summarizeEvery = 10)
        val m = msgs(15) // size - keepLast = 11 (odd)

        assertNotNull(c.maybeCompact(m, fake))
        assertEquals(10, c.coveredCount) // evenDown(11)
        assertEquals(0, c.coveredCount % 2)
    }

    @Test
    fun `maybeCompact rolls the prior summary into the new one`() = runTest {
        val fake = FakeLlmApi().apply { queueText("UPDATED") }
        val c = HistoryCompressor(
            keepLast = 6, summarizeEvery = 10,
            initialSummary = "PRIOR", initialCoveredCount = 4,
        )
        val m = msgs(20) // foldable = 20 - 6 - 4 = 10 → fold [4, 14)

        val outcome = assertNotNull(c.maybeCompact(m, fake))
        assertEquals(14, c.coveredCount)
        assertEquals("UPDATED", outcome.newSummary)
        assertTrue(
            fake.calls[0].messages[0].text.contains("PRIOR"),
            "summarization prompt should carry the prior summary",
        )
    }

    @Test
    fun `maybeCompact leaves state untouched when the summarizer errors`() = runTest {
        val fake = FakeLlmApi() // empty queue → error result
        val c = HistoryCompressor(keepLast = 6, summarizeEvery = 10)
        val m = msgs(16)

        assertNull(c.maybeCompact(m, fake))
        assertNull(c.summaryText)
        assertEquals(0, c.coveredCount)
        assertEquals(1, fake.calls.size) // the call was attempted but failed
    }

    @Test
    fun `maybeCompact treats a blank reply as failure`() = runTest {
        val fake = FakeLlmApi().apply { queue(LlmResult(text = "   ")) }
        val c = HistoryCompressor(keepLast = 6, summarizeEvery = 10)
        val m = msgs(16)

        assertNull(c.maybeCompact(m, fake))
        assertNull(c.summaryText)
        assertEquals(0, c.coveredCount)
    }

    @Test
    fun `keepLast is snapped down to even`() = runTest {
        val fake = FakeLlmApi().apply { queueText("S") }
        val c = HistoryCompressor(keepLast = 5, summarizeEvery = 10) // 5 → 4
        val m = msgs(16)

        assertNotNull(c.maybeCompact(m, fake))
        // keepLast effectively 4 → newCovered = evenDown(16 - 4) = 12
        assertEquals(12, c.coveredCount)
    }

    // --- helpers -----------------------------------------------------

    /** Build [n] messages as USER/ASSISTANT pairs (even index = USER). */
    private fun msgs(n: Int): List<Message> = (0 until n).map { i ->
        if (i % 2 == 0) Message(Role.USER, "u$i") else Message(Role.ASSISTANT, "a$i")
    }
}

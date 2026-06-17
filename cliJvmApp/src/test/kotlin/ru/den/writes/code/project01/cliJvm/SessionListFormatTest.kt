package ru.den.writes.code.project01.cliJvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Formatting of `-sessions` rows ([formatSessionLine]). The compression
 * segment must appear only for sessions that actually compressed.
 */
class SessionListFormatTest {

    @Test
    fun `when non-compressed session formatted - then no compression segment in line`() {
        // given
        val sessionId = "demo-nocomp"

        // when
        val actual = formatSessionLine(
            sessionId = sessionId,
            messageCount = 42,
            totalTokens = 106004,
            costUsd = 0.09141,
        )

        // then
        val expected = "demo-nocomp\t42 messages\ttotal_tokens=106004\tcost=\$0.09141"
        assertEquals(expected, actual)
        assertFalse(actual.contains("compressed"))
    }

    @Test
    fun `when compressed session formatted - then covered watermark and overhead appended`() {
        // given
        val sessionId = "demo-comp"

        // when
        val actual = formatSessionLine(
            sessionId = sessionId,
            messageCount = 42,
            totalTokens = 67155,
            costUsd = 0.09869,
            coveredCount = 38,
            overheadTokens = 4521,
            overheadCostUsd = 0.00452,
        )

        // then
        val expected = "demo-comp\t42 messages\ttotal_tokens=67155\tcost=\$0.09869" +
            "\tcompressed(covered=38/42, overhead=4521tok \$0.00452)"
        assertEquals(expected, actual)
    }

    @Test
    fun `when session has non-main branch - then formatted as session-slash-branch`() {
        // given
        val sessionId = "demo"
        val branchId = "alt"

        // when
        val actual = formatSessionLine(
            sessionId = sessionId,
            branchId = branchId,
            messageCount = 4,
            totalTokens = 100,
            costUsd = 0.001,
        )

        // then
        assertTrue(actual.startsWith("demo/alt\t4 messages"), "got: $actual")
    }

    @Test
    fun `when session has facts - then facts overhead segment appended and no compressed segment`() {
        // given
        val sessionId = "demo-facts"

        // when
        val actual = formatSessionLine(
            sessionId = sessionId,
            messageCount = 12,
            totalTokens = 5000,
            costUsd = 0.005,
            factsPresent = true,
            factsOverheadTokens = 800,
            factsOverheadCostUsd = 0.0008,
        )

        // then
        assertTrue(actual.contains("\tfacts(overhead=800tok \$0.00080)"), "got: $actual")
        assertFalse(actual.contains("compressed"))
    }
}

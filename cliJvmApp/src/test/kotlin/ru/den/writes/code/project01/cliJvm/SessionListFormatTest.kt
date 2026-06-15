package ru.den.writes.code.project01.cliJvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Formatting of `-sessions` rows ([formatSessionLine]). The compression
 * segment must appear only for sessions that actually compressed.
 */
class SessionListFormatTest {

    @Test
    fun `non-compressed session has no compression segment`() {
        val line = formatSessionLine(
            sessionId = "demo-nocomp",
            messageCount = 42,
            totalTokens = 106004,
            costUsd = 0.09141,
        )
        assertEquals(
            "demo-nocomp\t42 messages\ttotal_tokens=106004\tcost=\$0.09141",
            line,
        )
        assertFalse(line.contains("compressed"))
    }

    @Test
    fun `compressed session appends covered watermark and summarization overhead`() {
        val line = formatSessionLine(
            sessionId = "demo-comp",
            messageCount = 42,
            totalTokens = 67155,
            costUsd = 0.09869,
            coveredCount = 38,
            overheadTokens = 4521,
            overheadCostUsd = 0.00452,
        )
        assertEquals(
            "demo-comp\t42 messages\ttotal_tokens=67155\tcost=\$0.09869" +
                "\tcompressed(covered=38/42, overhead=4521tok \$0.00452)",
            line,
        )
    }
}

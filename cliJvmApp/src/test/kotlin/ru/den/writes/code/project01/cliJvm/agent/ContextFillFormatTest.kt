package ru.den.writes.code.project01.cliJvm.agent

import ru.den.writes.code.project01.cliJvm.formatContextFill
import kotlin.test.Test
import kotlin.test.assertEquals

class ContextFillFormatTest {

    @Test
    fun `when prompt is non-trivial fraction of window - then formatted with one decimal pct`() {
        // given
        val promptTokens = 120_000
        val windowTokens = 1_000_000

        // when
        val actual = formatContextFill(promptTokens = promptTokens, windowTokens = windowTokens)

        // then
        val expected = "context: 120000 / 1000000 (12.0%)"
        assertEquals(expected, actual)
    }

    @Test
    fun `when prompt is zero or fills the window - then edge cases formatted with one decimal pct`() {
        // given
        val windowTokens = 1_000_000

        // when
        val emptyActual = formatContextFill(promptTokens = 0, windowTokens = windowTokens)
        val fullActual = formatContextFill(promptTokens = windowTokens, windowTokens = windowTokens)

        // then
        assertEquals("context: 0 / 1000000 (0.0%)", emptyActual)
        assertEquals("context: 1000000 / 1000000 (100.0%)", fullActual)
    }
}

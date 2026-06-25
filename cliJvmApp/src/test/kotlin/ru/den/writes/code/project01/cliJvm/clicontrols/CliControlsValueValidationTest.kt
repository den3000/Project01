package ru.den.writes.code.project01.cliJvm.clicontrols

import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STAGES
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STRATEGY
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SUMMARIZE_EVERY
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.CMD
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.FLAG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PROTOTYPE: value-level validation off the shared catalog — bad value kinds,
 * wrong surfaces and value-conditional sub-options. Uses the single-control
 * [CliControlsParser.parse] path (no argv cross-validation), so each assertion
 * pins the exact [ParseError] the catalog produces.
 */
class CliControlsValueValidationTest {

    private val parser = CliControlsParser()

    //region stage range

    @Test
    fun `when a stage range runs backwards - then BadValue`() {
        // given
        val line = "-agent x stages execution..planning"

        // when
        val actual = err(line, FLAG)

        // then
        val expected = ParseError.BadValue(STAGES, "execution..planning", "a stage range with from no later than to")
        assertEquals(expected, actual)
    }
    //endregion

    //region value-conditional subs

    @Test
    fun `when summarizeEvery is used under a non-summary strategy - then WrongParentValue`() {
        // given
        val line = "/strategy window summarizeEvery 5"

        // when
        val actual = err(line, CMD)

        // then
        val expected = ParseError.WrongParentValue(SUMMARIZE_EVERY, STRATEGY, "window", setOf("summary"))
        assertEquals(expected, actual)
    }

    @Test
    fun `when summarizeEvery is used under strategy summary - then it parses`() {
        // given
        val line = "/strategy summary summarizeEvery 5"

        // when
        val actual = ok(line, CMD)

        // then
        assertEquals("5", actual.sub(SUMMARIZE_EVERY)?.value)
    }
    //endregion

    //region helpers

    private fun ok(line: String, surface: Surface): ParsedControl {
        val r = parser.parse(line, surface)
        assertTrue(r is ParseResult.Ok, "expected Ok for '$line', got $r")
        return (r as ParseResult.Ok).control
    }

    private fun err(line: String, surface: Surface): ParseError {
        val r = parser.parse(line, surface)
        assertTrue(r is ParseResult.Err, "expected Err for '$line', got $r")
        return (r as ParseResult.Err).error
    }
    //endregion
}

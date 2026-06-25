package ru.den.writes.code.project01.cliJvm.clicontrols

import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CHUNK_CHARS
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.INFLATE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MAX_TOKENS
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MODE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PROVIDER
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SESSION
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

    //region bad value kinds

    @Test
    fun `when a numeric value is out of range - then BadValue with the bound`() {
        // given
        val cases = listOf(
            "-agent x maxTokens abc" to ParseError.BadValue(MAX_TOKENS, "abc", "an integer"),
            "-feedFile d.txt chunkChars 0" to ParseError.BadValue(CHUNK_CHARS, "0", "an integer >= 1"),
            "-inflate 0" to ParseError.BadValue(INFLATE, "0", "an integer >= 1"),
            "-strategy summary summarizeEvery 1" to ParseError.BadValue(SUMMARIZE_EVERY, "1", "an integer >= 2"),
        )

        // when - then — one invariant over an extending list (rule §11.E)
        cases.forEach { (line, expected) ->
            assertEquals(expected, err(line, FLAG), line)
        }
    }

    @Test
    fun `when an enum value is unknown - then BadValue listing the options`() {
        // given
        val cases = listOf(
            "-strategy bogus" to ParseError.BadValue(STRATEGY, "bogus", "one of: full, window, facts, summary"),
            "-agent x provider bogus" to ParseError.BadValue(PROVIDER, "bogus", "one of: gemini, openrouter, huggingface"),
            "-agent x mode bogus" to ParseError.BadValue(MODE, "bogus", "one of: none, system, preamble"),
        )

        // when - then — one invariant over an extending list (rule §11.E)
        cases.forEach { (line, expected) ->
            assertEquals(expected, err(line, FLAG), line)
        }
    }

    @Test
    fun `when a session name is too long - then BadValue`() {
        // given
        val name = "a".repeat(65)

        // when
        val actual = err("-session $name", FLAG)

        // then
        assertEquals(ParseError.BadValue(SESSION, name, "alphanumeric / '_' / '-', up to 64 chars"), actual)
    }
    //endregion

    //region wrong surface

    @Test
    fun `when a sub-only token is used as a flag - then WrongSurface`() {
        // given — keepLast/chunkChars/provider exist only as subs, never top-level flags
        val cases = listOf(
            "-keepLast 4" to "keepLast",
            "-chunkChars 9" to "chunkChars",
            "-provider gemini" to "provider",
        )

        // when - then — one invariant over an extending list (rule §11.E)
        cases.forEach { (line, token) ->
            assertEquals(ParseError.WrongSurface(token, FLAG), err(line, FLAG), line)
        }
    }
    //endregion

    //region leftover tokens

    @Test
    fun `when a prompt has trailing unquoted words - then UnexpectedToken`() {
        // given — the shell must quote multi-word values; bare trailing words are leftovers
        val line = "-prompt tell me a joke"

        // when
        val actual = err(line, FLAG)

        // then
        assertEquals(ParseError.UnexpectedToken("me"), actual)
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

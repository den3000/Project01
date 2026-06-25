package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg
import ru.den.writes.code.project01.cliJvm.clicontrols.ParseError
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ParseErrorMappingTest {

    //region missing-required mappings

    @Test
    fun `when MissingValue - then MissingRequiredArgument on the flag`() {
        // when
        val actual = ParseError.MissingValue(CliControlsArg.PROMPT).toCliArgsException()

        // then
        assertEquals("-prompt", assertIs<CliArgsException.MissingRequiredArgument>(actual).argName)
    }

    @Test
    fun `when Requires - then MissingRequiredArgument on the missing flag`() {
        // when
        val actual = ParseError.Requires(CliControlsArg.INFLATE, CliControlsArg.SESSION).toCliArgsException()

        // then
        assertEquals("-session", assertIs<CliArgsException.MissingRequiredArgument>(actual).argName)
    }

    @Test
    fun `when Empty - then MissingRequiredArgument`() {
        // when
        val actual = ParseError.Empty.toCliArgsException()

        // then
        assertEquals("(input)", assertIs<CliArgsException.MissingRequiredArgument>(actual).argName)
    }
    //endregion

    //region invalid-value mappings

    @Test
    fun `when a value-ish error - then InvalidArgumentValue carrying arg, raw and reason`() {
        // given
        val cases = listOf(
            ParseError.BadValue(CliControlsArg.MAX_TOKENS, "abc", "an integer")
                to Triple("-maxTokens", "abc", "an integer"),
            ParseError.Conflicts(CliControlsArg.ONESHOT, CliControlsArg.TUI)
                to Triple("-oneshot", "-tui", "not combinable with -tui"),
            ParseError.WrongSurface("reuse", Surface.FLAG)
                to Triple("reuse", "flag", "valid on this surface"),
            ParseError.UnknownControl("nope")
                to Triple("nope", "nope", "a known control"),
            ParseError.ValueNotAllowedHere(CliControlsArg.SESSION, Surface.CMD)
                to Triple("-session", "cmd", "no value on this surface"),
            ParseError.WrongParentValue(CliControlsArg.SUMMARIZE_EVERY, CliControlsArg.STRATEGY, "window", setOf("summary"))
                to Triple("-summarizeEvery", "window", "parent in summary"),
            ParseError.UnexpectedToken("me")
                to Triple("me", "me", "not expected here"),
            ParseError.BadPrefix("xyz", Surface.FLAG)
                to Triple("xyz", "xyz", "a '-' control"),
        )

        // when - then — one invariant over an extending list (rule §11.E)
        cases.forEach { (error, expected) ->
            val (argName, raw, reason) = expected
            val ex = assertIs<CliArgsException.InvalidArgumentValue>(error.toCliArgsException(), "$error")
            assertEquals(argName, ex.argName, "$error argName")
            assertEquals(raw, ex.rawValue, "$error rawValue")
            assertEquals(reason, ex.expectedType, "$error expectedType")
        }
    }
    //endregion
}

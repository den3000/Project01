package ru.den.writes.code.project01.cliJvm.clicontrols.grammar

import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.EXIT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.HELP
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.REUSE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsParser
import ru.den.writes.code.project01.cliJvm.clicontrols.ExpectedControl
import ru.den.writes.code.project01.cliJvm.clicontrols.ParseError
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.CMD
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.FLAG
import ru.den.writes.code.project01.cliJvm.clicontrols.assertMatchParserCmd
import ru.den.writes.code.project01.cliJvm.clicontrols.assertMatchParserError
import ru.den.writes.code.project01.cliJvm.clicontrols.toArgsList
import kotlin.test.Test

/**
 * Command-only bare controls: reuse / exit / help. Parse on the CMD front; using
 * them as a `-flag` is WrongSurface. (Whether they are then *handled* in-session
 * is a mapping concern, not parsing — see the mapping layer.)
 */
class CliCommandOnlyGrammarTest {

    //region reuse / exit / help
    @Test
    fun `when a command-only control is used on its front - then it parses bare`() {
        // when
        val parser = CliControlsParser()

        // then
        assertMatchParserCmd("/reuse", ExpectedControl(surface = CMD, arg = REUSE), parser)
        assertMatchParserCmd("/exit", ExpectedControl(surface = CMD, arg = EXIT), parser)
        assertMatchParserCmd("/help", ExpectedControl(surface = CMD, arg = HELP), parser)
    }

    @Test
    fun `when a command-only control is used as a flag - then wrong surface`() {
        // when
        val parser = CliControlsParser()

        // then
        assertMatchParserError("-reuse".toArgsList(), ParseError.WrongSurface("reuse", FLAG), parser)
        assertMatchParserError("-exit".toArgsList(), ParseError.WrongSurface("exit", FLAG), parser)
        assertMatchParserError("-help".toArgsList(), ParseError.WrongSurface("help", FLAG), parser)
    }

    @Test
    fun `when an unknown control is typed - then UnknownControl`() {
        // when
        val parser = CliControlsParser()

        // then
        assertMatchParserError(CMD, "/nope", ParseError.UnknownControl("nope"), parser)
    }
    //endregion
}

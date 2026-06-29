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
        // given
        val sfc = CMD
        val controls = listOf(REUSE, EXIT, HELP)

        // when
        val parser = CliControlsParser()

        // then
        controls.forEach { cli ->
            assertMatchParserCmd("/${cli.title}", ExpectedControl(surface = sfc, arg = cli), parser)
        }
    }

    @Test
    fun `when a command-only control is used as a flag - then wrong surface`() {
        // given
        val sfc = FLAG
        val controls = listOf(REUSE, EXIT, HELP)

        // when
        val parser = CliControlsParser()

        // then
        controls.forEach { cli ->
            assertMatchParserError("-${cli.title}".toArgsList(), ParseError.WrongSurface(cli.title, sfc), parser)
        }
    }

    @Test
    fun `when an unknown control is typed - then UnknownControl`() {
        // given
        val sfc = CMD
        val unknown = "nope"

        // when
        val parser = CliControlsParser()

        // then
        assertMatchParserError(sfc, "/$unknown", ParseError.UnknownControl(unknown), parser)
    }
    //endregion
}

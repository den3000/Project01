package ru.den.writes.code.agenticHub.cliJvm.cliargs.grammar

import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.EXIT
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.HELP
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.REUSE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArgsParser
import ru.den.writes.code.agenticHub.cliJvm.cliargs.ExpectedControl
import ru.den.writes.code.agenticHub.cliJvm.cliargs.ParseError
import ru.den.writes.code.agenticHub.cliJvm.cliargs.Surface.CMD
import ru.den.writes.code.agenticHub.cliJvm.cliargs.Surface.FLAG
import ru.den.writes.code.agenticHub.cliJvm.cliargs.assertMatchParserCmd
import ru.den.writes.code.agenticHub.cliJvm.cliargs.assertMatchParserError
import ru.den.writes.code.agenticHub.cliJvm.cliargs.toArgsList
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
        val parser = CliArgsParser()

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
        val parser = CliArgsParser()

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
        val parser = CliArgsParser()

        // then
        assertMatchParserError(sfc, "/$unknown", ParseError.UnknownControl(unknown), parser)
    }
    //endregion
}

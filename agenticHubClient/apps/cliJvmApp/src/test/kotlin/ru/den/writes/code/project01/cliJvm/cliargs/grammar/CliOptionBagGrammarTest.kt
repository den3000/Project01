package ru.den.writes.code.project01.cliJvm.cliargs.grammar

import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.INFLATE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.MCP_SERVER
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.MEMORY
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.ONESHOT
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.PROMPT
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.SESSION
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.TUI
import ru.den.writes.code.project01.cliJvm.cliargs.CliArgsParser
import ru.den.writes.code.project01.cliJvm.cliargs.ExpectedControl
import ru.den.writes.code.project01.cliJvm.cliargs.ParseError
import ru.den.writes.code.project01.cliJvm.cliargs.Surface.CMD
import ru.den.writes.code.project01.cliJvm.cliargs.Surface.FLAG
import ru.den.writes.code.project01.cliJvm.cliargs.assertMatchParserCmd
import ru.den.writes.code.project01.cliJvm.cliargs.assertMatchParserError
import ru.den.writes.code.project01.cliJvm.cliargs.assertMatchParserFlag
import ru.den.writes.code.project01.cliJvm.cliargs.toArgsList
import ru.den.writes.code.project01.cliJvm.cliargs.top
import kotlin.test.Test

/**
 * Bag controls without entity subs: prompt / oneshot / tui (startup-only),
 * memory / mcpServer / inflate (both fronts). Cross-control rules (oneshot
 * excludes, inflate requires session) live in the crossvalidation suite; here
 * inflate's FLAG positive carries a session so the argv-level validator stays happy.
 */
class CliOptionBagGrammarTest {

    //region prompt
    @Test
    fun `when prompt flag grammar used - then it is parsed accordingly`() {
        // given
        val cli = PROMPT
        val sfc = FLAG
        val cmd = "-prompt"
        val text = "hi there"

        // when
        val parser = CliArgsParser()

        // then
        assertMatchParserFlag("$cmd \"$text\"".toArgsList(), top(cli, sfc, value = text), parser)
        assertMatchParserFlag("$cmd -v".toArgsList(), top(cli, sfc, value = "-v"), parser)
        assertMatchParserError("$cmd".toArgsList(), ParseError.MissingValue(cli), parser)
        assertMatchParserError("$cmd tell me".toArgsList(), ParseError.UnexpectedToken("me"), parser)
        assertMatchParserError(CMD, "/${cli.title} hi", ParseError.WrongSurface(cli.title, CMD), parser)
    }
    //endregion

    //region oneshot
    @Test
    fun `when oneshot flag grammar used - then it is parsed accordingly`() {
        // given
        val cli = ONESHOT
        val sfc = FLAG
        val cmd = "-oneshot"

        // when
        val parser = CliArgsParser()

        // then
        assertMatchParserFlag("$cmd".toArgsList(), top(cli, sfc), parser)
        assertMatchParserError(CMD, "/${cli.title}", ParseError.WrongSurface(cli.title, CMD), parser)
    }
    //endregion

    //region tui
    @Test
    fun `when tui flag grammar used - then it is parsed accordingly`() {
        // given
        val cli = TUI
        val sfc = FLAG
        val cmd = "-tui"

        // when
        val parser = CliArgsParser()

        // then
        assertMatchParserFlag("$cmd".toArgsList(), top(cli, sfc), parser)
        assertMatchParserError(CMD, "/${cli.title}", ParseError.WrongSurface(cli.title, CMD), parser)
    }
    //endregion

    //region memory
    @Test
    fun `when memory command grammar used - then it is parsed accordingly`() {
        // given
        val cli = MEMORY
        val sfc = CMD
        val cmd = "/memory"

        // when
        val parser = CliArgsParser()

        // then
        assertMatchParserCmd("$cmd", ExpectedControl(surface = sfc, arg = cli), parser)
    }

    @Test
    fun `when memory flag grammar used - then it is parsed accordingly`() {
        // given
        val cli = MEMORY
        val sfc = FLAG
        val cmd = "-memory"

        // when
        val parser = CliArgsParser()

        // then
        assertMatchParserFlag("$cmd".toArgsList(), top(cli, sfc), parser)
    }
    //endregion

    //region mcpServer
    @Test
    fun `when mcpServer command grammar used - then the quoted command stays one value`() {
        // given
        val cli = MCP_SERVER
        val sfc = CMD
        val cmd = "/mcpServer"
        val serverCmd = "mcpLab --serve"

        // when
        val parser = CliArgsParser()

        // then
        assertMatchParserCmd("$cmd \"$serverCmd\"", ExpectedControl(surface = sfc, arg = cli, value = serverCmd), parser)
    }

    @Test
    fun `when mcpServer flag grammar used - then the quoted command stays one value`() {
        // given
        val cli = MCP_SERVER
        val sfc = FLAG
        val cmd = "-mcpServer"
        val serverCmd = "mcpLab --serve"

        // when
        val parser = CliArgsParser()

        // then
        assertMatchParserFlag("$cmd \"$serverCmd\"".toArgsList(), top(cli, sfc, value = serverCmd), parser)
    }
    //endregion

    //region inflate
    @Test
    fun `when inflate command grammar used - then it is parsed accordingly`() {
        // given
        val cli = INFLATE
        val sfc = CMD
        val cmd = "/inflate"
        val n = "5"

        // when
        val parser = CliArgsParser()

        // then
        assertMatchParserCmd("$cmd $n", ExpectedControl(surface = sfc, arg = cli, value = n), parser)
    }

    @Test
    fun `when inflate flag grammar used - then it is parsed accordingly`() {
        // given
        val cli = INFLATE
        val sfc = FLAG
        val cmd = "-inflate"
        val n = "5"

        // when
        val parser = CliArgsParser()

        // then
        assertMatchParserFlag("$cmd $n -session foo".toArgsList(), listOf(top(cli, sfc, value = n), top(SESSION, sfc, value = "foo")), parser)
        assertMatchParserError("$cmd 0".toArgsList(), ParseError.BadValue(cli, "0", "an integer >= 1"), parser)
        assertMatchParserError("$cmd abc".toArgsList(), ParseError.BadValue(cli, "abc", "an integer"), parser)
    }
    //endregion
}

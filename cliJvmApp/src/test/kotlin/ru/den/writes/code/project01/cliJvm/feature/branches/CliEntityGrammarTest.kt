package ru.den.writes.code.project01.cliJvm.feature.branches

import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.BRANCH
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CLEAR
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.RULE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SHOW
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SWITCH
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsParser
import ru.den.writes.code.project01.cliJvm.clicontrols.ExpectedControl
import ru.den.writes.code.project01.cliJvm.clicontrols.ParseError
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.CMD
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.FLAG
import ru.den.writes.code.project01.cliJvm.clicontrols.assertMatchParserCmd
import ru.den.writes.code.project01.cliJvm.clicontrols.assertMatchParserError
import ru.den.writes.code.project01.cliJvm.clicontrols.assertMatchParserFlag
import ru.den.writes.code.project01.cliJvm.clicontrols.sub
import ru.den.writes.code.project01.cliJvm.clicontrols.toArgsList
import ru.den.writes.code.project01.cliJvm.clicontrols.top
import kotlin.test.Test

class CliEntityGrammarTest {

    //region rule
    @Test
    fun `when rule command grammar used - then it is parsed accordingly`() {
        // given
        val cli = RULE
        val sfc = CMD
        val cmd = "/rule"
        val arg1 = "some content"
        val arg2 = "taskId" // string

        // when
        val parser = CliControlsParser()

        // then
        assertMatchParserCmd("$cmd", ExpectedControl(surface = sfc, arg = cli), parser)
        assertMatchParserCmd("$cmd \"$arg1\"", ExpectedControl(surface = sfc, arg = cli, value = arg1), parser)
        assertMatchParserCmd("$cmd show", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, SHOW))), parser)
        assertMatchParserCmd("$cmd show $arg2", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, SHOW, value = arg2))), parser)
        assertMatchParserCmd("$cmd clear", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, CLEAR))), parser)
        assertMatchParserCmd("$cmd clear $arg2", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, CLEAR, value = arg2))), parser)
    }

    @Test
    fun `when rule flag grammar used - then it is parsed accordingly`() {
        // given
        val cli = RULE
        val sfc = FLAG
        val cmd = "-rule"
        val arg1 = "some content"
        val arg2 = "taskId" // string

        // when
        val parser = CliControlsParser()

        // then
        assertMatchParserFlag("$cmd".toArgsList(), top(cli, sfc), parser)
        assertMatchParserFlag("$cmd \"$arg1\"".toArgsList(), top(cli, sfc, value = arg1), parser)
        assertMatchParserFlag("$cmd show".toArgsList(), top(cli, sfc, subs = listOf(sub(cli, SHOW))), parser)
        assertMatchParserFlag("$cmd show $arg2".toArgsList(), top(cli, sfc, subs = listOf(sub(cli, SHOW, value = arg2))), parser)
        assertMatchParserFlag("$cmd clear".toArgsList(), top(cli, sfc, subs = listOf(sub(cli, CLEAR))), parser)
        assertMatchParserFlag("$cmd clear $arg2".toArgsList(), top(cli, sfc, subs = listOf(sub(cli, CLEAR, value = arg2))), parser)

        assertMatchParserError("$cmd $arg1".toArgsList(), ParseError.UnexpectedToken("content"), parser)
    }
    //endregion

    //region branch
    @Test
    fun `when branch command grammar used - then it is parsed accordingly`() {
        // given
        val cli = BRANCH
        val sfc = CMD
        val cmd = "/branch"
        val arg = "branch_name"

        // when
        val parser = CliControlsParser()

        // then
        assertMatchParserCmd("$cmd", ExpectedControl(surface = sfc, arg = cli), parser)
        assertMatchParserCmd("$cmd $arg", ExpectedControl(surface = sfc, arg = cli, value = arg), parser)
        assertMatchParserCmd("$cmd show", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, SHOW))), parser)
        assertMatchParserCmd("$cmd show $arg", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, SHOW, value = arg))), parser)
        assertMatchParserCmd("$cmd clear", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, CLEAR))), parser)
        assertMatchParserCmd("$cmd clear $arg", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, CLEAR, value = arg))), parser)

        assertMatchParserCmd("$cmd switch $arg", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, SWITCH, value = arg))), parser)
        assertMatchParserError(CMD, "$cmd switch", ParseError.MissingValue(SWITCH), parser)
    }
    //endregion
}
package ru.den.writes.code.agenticHub.cliJvm.cliargs.grammar

import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.AFTER
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.ARGS
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.CLEAR
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.EVERY
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.MCP_SERVER
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.PROMPT
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.SCHEDULE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.TOOL
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArgsParser
import ru.den.writes.code.agenticHub.cliJvm.cliargs.ExpectedControl
import ru.den.writes.code.agenticHub.cliJvm.cliargs.ParseError
import ru.den.writes.code.agenticHub.cliJvm.cliargs.Surface.CMD
import ru.den.writes.code.agenticHub.cliJvm.cliargs.Surface.FLAG
import ru.den.writes.code.agenticHub.cliJvm.cliargs.assertMatchParserCmd
import ru.den.writes.code.agenticHub.cliJvm.cliargs.assertMatchParserError
import ru.den.writes.code.agenticHub.cliJvm.cliargs.assertMatchParserFlag
import ru.den.writes.code.agenticHub.cliJvm.cliargs.sub
import ru.den.writes.code.agenticHub.cliJvm.cliargs.toArgsList
import ru.den.writes.code.agenticHub.cliJvm.cliargs.top
import kotlin.test.Test

/**
 * `schedule <collect|agent>` + subs tool/args/prompt/after/every/clear. Conditional
 * subs (tool/args under `collect`, prompt under `agent`) are single-parse →
 * WrongParentValue, so they live here; cross-control rules (tool requires mcpServer,
 * after⊕every, schedule⊥oneshot) are argv-level → the crossvalidation suite.
 */
class CliScheduleGrammarTest {

    //region schedule
    @Test
    fun `when schedule command grammar used - then it is parsed accordingly`() {
        // given
        val cli = SCHEDULE
        val sfc = CMD
        val cmd = "/schedule"

        // when
        val parser = CliArgsParser()

        // then
        assertMatchParserCmd("$cmd", ExpectedControl(surface = sfc, arg = cli), parser)
        assertMatchParserCmd("$cmd clear", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, CLEAR))), parser)
        assertMatchParserCmd("$cmd clear 001", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, CLEAR, value = "001"))), parser)
        assertMatchParserCmd(
            "$cmd collect tool weather args \"{}\" after 30",
            ExpectedControl(surface = sfc, arg = cli, value = "collect", subs = listOf(sub(cli, TOOL, value = "weather"), sub(cli, ARGS, value = "{}"), sub(cli, AFTER, value = "30"))),
            parser,
        )
        assertMatchParserCmd(
            "$cmd agent prompt \"do x\" every 60",
            ExpectedControl(surface = sfc, arg = cli, value = "agent", subs = listOf(sub(cli, PROMPT, value = "do x"), sub(cli, EVERY, value = "60"))),
            parser,
        )
        // negatives — conditional subs and bad kind are single-parse
        assertMatchParserError(CMD, "$cmd agent tool weather", ParseError.WrongParentValue(TOOL, SCHEDULE, "agent", setOf("collect")), parser)
        assertMatchParserError(CMD, "$cmd collect prompt \"x\"", ParseError.WrongParentValue(PROMPT, SCHEDULE, "collect", setOf("agent")), parser)
        assertMatchParserError(CMD, "$cmd bogus", ParseError.BadValue(SCHEDULE, "bogus", "one of: collect, agent"), parser)
    }

    @Test
    fun `when schedule flag grammar used - then it is parsed accordingly`() {
        // given
        val cli = SCHEDULE
        val sfc = FLAG
        val cmd = "-schedule"

        // when
        val parser = CliArgsParser()

        // then — agent needs no mcpServer; collect's tool requires one (carried here so argv stays valid)
        assertMatchParserFlag(
            "$cmd agent prompt \"do x\" after 30".toArgsList(),
            top(cli, sfc, value = "agent", subs = listOf(sub(cli, PROMPT, value = "do x"), sub(cli, AFTER, value = "30"))),
            parser,
        )
        assertMatchParserFlag(
            "$cmd collect tool weather every 60 -mcpServer \"lab --serve\"".toArgsList(),
            listOf(
                top(cli, sfc, value = "collect", subs = listOf(sub(cli, TOOL, value = "weather"), sub(cli, EVERY, value = "60"))),
                top(MCP_SERVER, sfc, value = "lab --serve"),
            ),
            parser,
        )
    }
    //endregion
}

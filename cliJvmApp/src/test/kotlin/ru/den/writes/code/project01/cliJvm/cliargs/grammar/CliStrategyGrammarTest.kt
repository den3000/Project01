package ru.den.writes.code.project01.cliJvm.cliargs.grammar

import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.KEEP_LAST
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.STRATEGY
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.SUMMARIZE_EVERY
import ru.den.writes.code.project01.cliJvm.cliargs.CliArgsParser
import ru.den.writes.code.project01.cliJvm.cliargs.ExpectedControl
import ru.den.writes.code.project01.cliJvm.cliargs.ParseError
import ru.den.writes.code.project01.cliJvm.cliargs.Surface.CMD
import ru.den.writes.code.project01.cliJvm.cliargs.Surface.FLAG
import ru.den.writes.code.project01.cliJvm.cliargs.assertMatchParserCmd
import ru.den.writes.code.project01.cliJvm.cliargs.assertMatchParserError
import ru.den.writes.code.project01.cliJvm.cliargs.assertMatchParserFlag
import ru.den.writes.code.project01.cliJvm.cliargs.sub
import ru.den.writes.code.project01.cliJvm.cliargs.toArgsList
import ru.den.writes.code.project01.cliJvm.cliargs.top
import kotlin.test.Test

/**
 * `strategy <full|window|facts|summary>` plus keepLast / summarizeEvery. The
 * conditional sub (summarizeEvery valid only under `summary`) IS single-parse →
 * WrongParentValue, so it lives here, not in the crossvalidation suite.
 */
class CliStrategyGrammarTest {

    private val modes = listOf("full", "window", "facts", "summary")

    //region strategy
    @Test
    fun `when strategy command grammar used - then it is parsed accordingly`() {
        // given
        val cli = STRATEGY
        val sfc = CMD
        val cmd = "/strategy"

        // when
        val parser = CliArgsParser()

        // then
        modes.forEach { mode ->
            assertMatchParserCmd("$cmd $mode", ExpectedControl(surface = sfc, arg = cli, value = mode), parser)
        }
        assertMatchParserCmd("$cmd window keepLast 8", ExpectedControl(surface = sfc, arg = cli, value = "window", subs = listOf(sub(cli, KEEP_LAST, value = "8"))), parser)
        assertMatchParserCmd("$cmd summary keepLast 4 summarizeEvery 5", ExpectedControl(surface = sfc, arg = cli, value = "summary", subs = listOf(sub(cli, KEEP_LAST, value = "4"), sub(cli, SUMMARIZE_EVERY, value = "5"))), parser)
        assertMatchParserError(CMD, "$cmd bogus", ParseError.BadValue(STRATEGY, "bogus", "one of: full, window, facts, summary"), parser)
        assertMatchParserError(CMD, "$cmd window summarizeEvery 5", ParseError.WrongParentValue(SUMMARIZE_EVERY, STRATEGY, "window", setOf("summary")), parser)
        assertMatchParserError(CMD, "$cmd summary summarizeEvery 1", ParseError.BadValue(SUMMARIZE_EVERY, "1", "an integer >= 2"), parser)
    }

    @Test
    fun `when strategy flag grammar used - then it is parsed accordingly`() {
        // given
        val cli = STRATEGY
        val sfc = FLAG
        val cmd = "-strategy"

        // when
        val parser = CliArgsParser()

        // then
        modes.forEach { mode ->
            assertMatchParserFlag("$cmd $mode".toArgsList(), top(cli, sfc, value = mode), parser)
        }
        assertMatchParserFlag("$cmd window keepLast 8".toArgsList(), top(cli, sfc, value = "window", subs = listOf(sub(cli, KEEP_LAST, value = "8"))), parser)
        assertMatchParserFlag("$cmd summary keepLast 4 summarizeEvery 5".toArgsList(), top(cli, sfc, value = "summary", subs = listOf(sub(cli, KEEP_LAST, value = "4"), sub(cli, SUMMARIZE_EVERY, value = "5"))), parser)
        assertMatchParserError("$cmd window keepLast abc".toArgsList(), ParseError.BadValue(KEEP_LAST, "abc", "an integer"), parser)
        assertMatchParserError("$cmd window keepLast -3".toArgsList(), ParseError.BadValue(KEEP_LAST, "-3", "an integer >= 0"), parser)
        assertMatchParserError("-keepLast 4".toArgsList(), ParseError.WrongSurface("keepLast", FLAG), parser)
    }
    //endregion
}

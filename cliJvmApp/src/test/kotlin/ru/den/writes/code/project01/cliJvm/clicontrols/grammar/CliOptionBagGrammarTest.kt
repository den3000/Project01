package ru.den.writes.code.project01.cliJvm.clicontrols.grammar

import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.INFLATE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MCP_SERVER
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MEMORY
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.ONESHOT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PROMPT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SESSION
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.TUI
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsParser
import ru.den.writes.code.project01.cliJvm.clicontrols.ExpectedControl
import ru.den.writes.code.project01.cliJvm.clicontrols.ParseError
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.CMD
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.FLAG
import ru.den.writes.code.project01.cliJvm.clicontrols.assertMatchParserCmd
import ru.den.writes.code.project01.cliJvm.clicontrols.assertMatchParserError
import ru.den.writes.code.project01.cliJvm.clicontrols.assertMatchParserFlag
import ru.den.writes.code.project01.cliJvm.clicontrols.toArgsList
import ru.den.writes.code.project01.cliJvm.clicontrols.top
import kotlin.test.Test

/**
 * Bag controls without entity subs: prompt / oneshot / tui (startup-only),
 * memory / mcpServer / inflate (both fronts). Cross-control rules (oneshot
 * excludes, inflate requires session) live in the crossvalidation suite; here
 * inflate's FLAG positive carries a session so the argv-level validator stays happy.
 */
class CliOptionBagGrammarTest {

    //region prompt (startup-only, required text)
    @Test
    fun `when prompt grammar used - then it is parsed accordingly`() {
        // given
        val parser = CliControlsParser()

        // then
        assertMatchParserFlag("-prompt \"hi there\"".toArgsList(), top(PROMPT, FLAG, "hi there"), parser)
        assertMatchParserError("-prompt".toArgsList(), ParseError.MissingValue(PROMPT), parser)
        assertMatchParserError("-prompt tell me".toArgsList(), ParseError.UnexpectedToken("me"), parser)
        assertMatchParserError(CMD, "/prompt hi", ParseError.WrongSurface("prompt", CMD), parser)
    }
    //endregion

    //region oneshot / tui (startup-only, bare)
    @Test
    fun `when oneshot grammar used - then it is parsed accordingly`() {
        // given
        val parser = CliControlsParser()

        // then
        assertMatchParserFlag("-oneshot".toArgsList(), top(ONESHOT, FLAG), parser)
        assertMatchParserError(CMD, "/oneshot", ParseError.WrongSurface("oneshot", CMD), parser)
    }

    @Test
    fun `when tui grammar used - then it is parsed accordingly`() {
        // given
        val parser = CliControlsParser()

        // then
        assertMatchParserFlag("-tui".toArgsList(), top(TUI, FLAG), parser)
        assertMatchParserError(CMD, "/tui", ParseError.WrongSurface("tui", CMD), parser)
    }
    //endregion

    //region memory / mcpServer (both fronts, bare / quoted value)
    @Test
    fun `when memory grammar used - then it is parsed accordingly`() {
        // given
        val parser = CliControlsParser()

        // then
        assertMatchParserFlag("-memory".toArgsList(), top(MEMORY, FLAG), parser)
        assertMatchParserCmd("/memory", ExpectedControl(surface = CMD, arg = MEMORY), parser)
    }

    @Test
    fun `when mcpServer grammar used - then the quoted command stays one value`() {
        // given
        val parser = CliControlsParser()

        // then
        assertMatchParserFlag("-mcpServer \"mcpLab --serve\"".toArgsList(), top(MCP_SERVER, FLAG, "mcpLab --serve"), parser)
        assertMatchParserCmd("/mcpServer \"mcpLab --serve\"", ExpectedControl(surface = CMD, arg = MCP_SERVER, value = "mcpLab --serve"), parser)
    }
    //endregion

    //region inflate (both fronts, required int >= 1)
    @Test
    fun `when inflate grammar used - then it is parsed accordingly`() {
        // given
        val parser = CliControlsParser()

        // then — CMD single-parse needs no session; the FLAG/argv positive carries one (requires-session)
        assertMatchParserCmd("/inflate 5", ExpectedControl(surface = CMD, arg = INFLATE, value = "5"), parser)
        assertMatchParserFlag(
            "-inflate 5 -session foo".toArgsList(),
            listOf(top(INFLATE, FLAG, "5"), top(SESSION, FLAG, "foo")),
            parser,
        )
        assertMatchParserError("-inflate 0".toArgsList(), ParseError.BadValue(INFLATE, "0", "an integer >= 1"), parser)
        assertMatchParserError("-inflate abc".toArgsList(), ParseError.BadValue(INFLATE, "abc", "an integer"), parser)
    }
    //endregion
}

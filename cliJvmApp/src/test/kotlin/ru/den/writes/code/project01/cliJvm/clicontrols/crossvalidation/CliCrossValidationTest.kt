package ru.den.writes.code.project01.cliJvm.clicontrols.crossvalidation

import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.AGENT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.BY_LINE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CHUNK_CHARS
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.FEED_FILE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.INFLATE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.JUDGE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MAX_TOKENS
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MCP_SERVER
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MODE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.ONESHOT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PROFILE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PROMPT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.RULE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SCHEDULE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SESSION
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STAGES
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STRATEGY
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.TASK
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.TOOL
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.TUI
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsParser
import ru.den.writes.code.project01.cliJvm.clicontrols.ParseError
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.FLAG
import ru.den.writes.code.project01.cliJvm.clicontrols.assertMatchParserError
import ru.den.writes.code.project01.cliJvm.clicontrols.assertMatchParserFlag
import ru.den.writes.code.project01.cliJvm.clicontrols.sub
import ru.den.writes.code.project01.cliJvm.clicontrols.toArgsList
import ru.den.writes.code.project01.cliJvm.clicontrols.top
import kotlin.test.Test

/**
 * Cross-control validation — the declarative `requires` / `excludes` the batch
 * validator runs after each group parses. This is argv-level only: it fires from
 * [CliControlsParser.parseArgv], never from a single-control `parse`, so the
 * grammar suites (per-control) can't express it — it lives here.
 */
class CliCrossValidationTest {

    private val parser = CliControlsParser()

    //region oneshot exclusivity
    @Test
    fun `when oneshot is combined with an incompatible control - then that control conflicts`() {
        // given — oneshot is one turn (no session/feed/memory/FSM/TUI/tools); top-level controls
        // carry the exclude, and the agent's mode/stages/judge subs carry it too
        val cases = listOf(
            "-session foo" to SESSION,
            "-strategy window" to STRATEGY,
            "-feedFile /tmp/x" to FEED_FILE,
            "-profile coder" to PROFILE,
            "-task auth" to TASK,
            "-rule \"no spring\"" to RULE,
            "-tui" to TUI,
            "-mcpServer \"mcpLab --serve\"" to MCP_SERVER,
            "-agent main mode system" to MODE,
            "-agent main stages execution..done" to STAGES,
            "-agent checker judge" to JUDGE,
            "-schedule agent prompt \"recap\" every 30" to SCHEDULE,
        )

        // when - then — one invariant (oneshot excludes each) over an extending list
        cases.forEach { (tail, arg) ->
            assertMatchParserError("-prompt hi -oneshot $tail".toArgsList(), ParseError.Conflicts(arg, ONESHOT), parser)
        }
    }

    @Test
    fun `when oneshot carries a generation-only agent - then it is valid`() {
        // given — oneshot picks up an agent for generation params + profile; only the multi-turn
        // subs conflict, not the agent itself or its knobs
        assertMatchParserFlag(
            "-prompt hi -oneshot -agent main profile coder maxTokens 42".toArgsList(),
            listOf(
                top(PROMPT, FLAG, "hi"),
                top(ONESHOT, FLAG),
                top(AGENT, FLAG, "main", listOf(sub(AGENT, PROFILE, "coder"), sub(AGENT, MAX_TOKENS, "42"))),
            ),
            parser,
        )
    }
    //endregion

    //region inflate requires a session
    @Test
    fun `when inflate is used without a session - then it requires one`() {
        // when - then
        assertMatchParserError("-inflate 5".toArgsList(), ParseError.Requires(INFLATE, SESSION), parser)
    }

    @Test
    fun `when inflate is paired with a session - then it is valid`() {
        // when - then
        assertMatchParserFlag(
            "-inflate 5 -session foo".toArgsList(),
            listOf(top(INFLATE, FLAG, "5"), top(SESSION, FLAG, "foo")),
            parser,
        )
    }
    //endregion

    //region feed mode exclusivity
    @Test
    fun `when a feed is split by both line and chunk - then both reciprocal excludes fire`() {
        // given — byLine and chunkChars each declare the other excluded, so both conflicts fire
        assertMatchParserError(
            "-feedFile d.txt byLine chunkChars 100".toArgsList(),
            listOf(ParseError.Conflicts(BY_LINE, CHUNK_CHARS), ParseError.Conflicts(CHUNK_CHARS, BY_LINE)),
            parser,
        )
    }
    //endregion

    //region schedule requires mcpServer
    @Test
    fun `when a collect schedule lacks an mcp server - then its tool requires one`() {
        // when - then — collect's tool calls an MCP tool, so it needs -mcpServer
        assertMatchParserError(
            "-prompt hi -schedule collect tool weather every 30".toArgsList(),
            ParseError.Requires(TOOL, MCP_SERVER),
            parser,
        )
    }
    //endregion
}

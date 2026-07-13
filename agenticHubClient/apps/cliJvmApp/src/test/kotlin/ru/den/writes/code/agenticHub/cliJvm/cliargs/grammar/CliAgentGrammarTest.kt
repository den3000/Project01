package ru.den.writes.code.agenticHub.cliJvm.cliargs.grammar

import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.AGENT
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.CLEAR
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.CONTEXT_WINDOW
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.END_SEQUENCE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.HOST
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.JUDGE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.MAX_TOKENS
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.MODE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.MODEL
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.PROFILE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.PROVIDER
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.SEED
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.SHOW
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.STAGES
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.STOP_SEQUENCE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.TEMPERATURE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.TOP_P
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
 * `agent` grammar — an entity (bare / show / clear) whose extras are a flat bag of
 * config subs (provider/model/knobs/profile/mode/stages/judge). The agent's own
 * `profile` sub binds a profile to this agent and is distinct from the `profile`
 * entity (built via [sub] under AGENT). Cross-control negatives (oneshot vs
 * mode/stages/judge) are argv-level — see the crossvalidation suite, not here.
 */
class CliAgentGrammarTest {

    //region agent
    @Test
    fun `when agent command grammar used - then it is parsed accordingly`() {
        // given
        val cli = AGENT
        val sfc = CMD
        val cmd = "/agent"
        val name = "main"

        // when
        val parser = CliArgsParser()

        // then
        assertMatchParserCmd("$cmd $name", ExpectedControl(surface = sfc, arg = cli, value = name), parser)
        assertMatchParserCmd("$cmd show", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, SHOW))), parser)
        assertMatchParserCmd("$cmd clear $name", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, CLEAR, value = name))), parser)
        assertMatchParserCmd(
            "$cmd $name provider ollama model qwen2.5:1.5b host http://ollama.local profile coder mode system stages execution..done",
            ExpectedControl(
                surface = sfc, arg = cli, value = name,
                subs = listOf(
                    sub(cli, PROVIDER, value = "ollama"),
                    sub(cli, MODEL, value = "qwen2.5:1.5b"),
                    sub(cli, HOST, value = "http://ollama.local"),
                    sub(cli, PROFILE, value = "coder"),
                    sub(cli, MODE, value = "system"),
                    sub(cli, STAGES, value = "execution..done"),
                ),
            ),
            parser,
        )
        assertMatchParserCmd(
            "$cmd checker judge stages execution..done",
            ExpectedControl(surface = sfc, arg = cli, value = "checker", subs = listOf(sub(cli, JUDGE), sub(cli, STAGES, value = "execution..done"))),
            parser,
        )
        assertMatchParserCmd(
            "$cmd $name maxTokens 200 temperature 0.7 topP 0.9 seed 42 contextWindow 8192 stopSequence \"</end>\" endSequence \"###\"",
            ExpectedControl(
                surface = sfc, arg = cli, value = name,
                subs = listOf(
                    sub(cli, MAX_TOKENS, value = "200"),
                    sub(cli, TEMPERATURE, value = "0.7"),
                    sub(cli, TOP_P, value = "0.9"),
                    sub(cli, SEED, value = "42"),
                    sub(cli, CONTEXT_WINDOW, value = "8192"),
                    sub(cli, STOP_SEQUENCE, value = "</end>"),
                    sub(cli, END_SEQUENCE, value = "###"),
                ),
            ),
            parser,
        )
        assertMatchParserError(CMD, "$cmd mode", ParseError.MissingValue(MODE), parser)
        assertMatchParserError(CMD, "$cmd x mode loud", ParseError.BadValue(MODE, "loud", "one of: none, system, preamble"), parser)
    }

    @Test
    fun `when agent flag grammar used - then it is parsed accordingly`() {
        // given
        val cli = AGENT
        val sfc = FLAG
        val cmd = "-agent"
        val name = "main"

        // when
        val parser = CliArgsParser()

        // then — entity ops
        assertMatchParserFlag("$cmd $name".toArgsList(), top(cli, sfc, value = name), parser)
        assertMatchParserFlag("$cmd show".toArgsList(), top(cli, sfc, subs = listOf(sub(cli, SHOW))), parser)
        assertMatchParserFlag("$cmd clear $name".toArgsList(), top(cli, sfc, subs = listOf(sub(cli, CLEAR, value = name))), parser)
        assertMatchParserFlag(
            "$cmd $name provider ollama model qwen2.5:1.5b host http://ollama.local profile coder mode system stages execution..done".toArgsList(),
            top(
                cli, sfc, value = name,
                subs = listOf(
                    sub(cli, PROVIDER, value = "ollama"),
                    sub(cli, MODEL, value = "qwen2.5:1.5b"),
                    sub(cli, HOST, value = "http://ollama.local"),
                    sub(cli, PROFILE, value = "coder"),
                    sub(cli, MODE, value = "system"),
                    sub(cli, STAGES, value = "execution..done"),
                ),
            ),
            parser,
        )
        assertMatchParserFlag(
            "$cmd checker judge stages execution..done".toArgsList(),
            top(cli, sfc, value = "checker", subs = listOf(sub(cli, JUDGE), sub(cli, STAGES, value = "execution..done"))),
            parser,
        )
        assertMatchParserFlag(
            "$cmd $name maxTokens 200 temperature 0.7 topP 0.9 seed 42 contextWindow 8192 stopSequence \"</end>\" endSequence \"###\"".toArgsList(),
            top(
                cli, sfc, value = name,
                subs = listOf(
                    sub(cli, MAX_TOKENS, value = "200"),
                    sub(cli, TEMPERATURE, value = "0.7"),
                    sub(cli, TOP_P, value = "0.9"),
                    sub(cli, SEED, value = "42"),
                    sub(cli, CONTEXT_WINDOW, value = "8192"),
                    sub(cli, STOP_SEQUENCE, value = "</end>"),
                    sub(cli, END_SEQUENCE, value = "###"),
                ),
            ),
            parser,
        )
        assertMatchParserError("$cmd x temperature 9".toArgsList(), ParseError.BadValue(TEMPERATURE, "9", "a number in 0.0..2.0"), parser)
        assertMatchParserError("$cmd x topP 2".toArgsList(), ParseError.BadValue(TOP_P, "2", "a number in 0.0..1.0"), parser)
        assertMatchParserError("$cmd x contextWindow abc".toArgsList(), ParseError.BadValue(CONTEXT_WINDOW, "abc", "an integer"), parser)
        assertMatchParserError("$cmd x stages foo..bar".toArgsList(), ParseError.BadValue(STAGES, "foo..bar", "a stage range like clarification..planning"), parser)
        assertMatchParserError("$cmd x provider bogus".toArgsList(), ParseError.BadValue(PROVIDER, "bogus", "one of: gemini, openrouter, huggingface, ollama"), parser)
        assertMatchParserError("$cmd x stages execution..planning".toArgsList(), ParseError.BadValue(STAGES, "execution..planning", "a stage range with from no later than to"), parser)
        assertMatchParserError("$cmd x maxTokens abc".toArgsList(), ParseError.BadValue(MAX_TOKENS, "abc", "an integer"), parser)
        assertMatchParserError("-provider gemini".toArgsList(), ParseError.WrongSurface("provider", FLAG), parser)
    }
    //endregion
}

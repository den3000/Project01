package ru.den.writes.code.project01.cliJvm.clicontrols

import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.FEED_FILE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.JUDGE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MODE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.ONESHOT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PROFILE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.RULE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SESSION
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STAGES
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STRATEGY
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.TASK
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PROTOTYPE: startup-argv cross-validation — the declarative `requires` / `excludes`
 * the batch validator runs after each group parses. Assertions pin the exact
 * [ParseError] list [CliControlsParser.parseArgv] collects.
 */
class CliControlsCrossValidationTest {

    private val parser = CliControlsParser()

    //region oneshot exclusivity

    @Test
    fun `when oneshot is combined with a multi-turn control - then that control conflicts`() {
        // given — oneshot is one turn (no session/feed/memory/FSM); each tail implies otherwise.
        // top-level controls carry the exclude themselves; agent's mode/stages/judge subs carry it
        // too, while the agent and its generation knobs stay allowed (see the next test).
        val cases = listOf(
            listOf("-session", "foo") to SESSION,
            listOf("-strategy", "window") to STRATEGY,
            listOf("-feedFile", "/tmp/x") to FEED_FILE,
            listOf("-profile", "coder") to PROFILE,
            listOf("-task", "auth") to TASK,
            listOf("-rule", "no spring") to RULE,
            listOf("-agent", "main", "mode", "system") to MODE,
            listOf("-agent", "main", "stages", "execution..done") to STAGES,
            listOf("-agent", "checker", "judge") to JUDGE,
        )

        // when - then — one invariant (oneshot excludes each) over an extending list (rule §11.E)
        cases.forEach { (tail, arg) ->
            val actual = parser.parseArgv(listOf("-prompt", "hi", "-oneshot") + tail)
            assertEquals(listOf<ParseError>(ParseError.Conflicts(arg, ONESHOT)), actual.errors, "oneshot vs ${arg.title}")
        }
    }

    @Test
    fun `when oneshot carries a generation-only agent - then it is valid`() {
        // given — oneshot picks up an agent for generation params + profile (README); only the
        // multi-turn sub-options conflict, not the agent itself or its knobs
        val args = listOf("-prompt", "hi", "-oneshot", "-agent", "main", "profile", "coder", "maxTokens", "42")

        // when
        val actual = parser.parseArgv(args)

        // then
        assertEquals(emptyList<ParseError>(), actual.errors, "errors: ${actual.errors.map { it.message }}")
    }
    //endregion
}

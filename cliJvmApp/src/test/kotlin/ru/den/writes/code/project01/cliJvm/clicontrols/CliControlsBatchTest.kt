package ru.den.writes.code.project01.cliJvm.clicontrols

import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.KEEP_LAST
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PROMPT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STRATEGY
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.FLAG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PROTOTYPE demo: the startup-argv side — splitting one argv into control groups
 * (including `-`-leading values) — plus a couple of catalog-integrity checks.
 * Assertions pin the exact [BatchResult]; cross-control `requires` / `excludes`
 * live in [CliControlsCrossValidationTest].
 */
class CliControlsBatchTest {

    private val parser = CliControlsParser()

    //region argv splitting

    @Test
    fun `when an argv carries several controls - then each parses and the batch is valid`() {
        // given
        val args = listOf("-prompt", "hi", "-strategy", "window", "keepLast", "8")

        // when
        val actual = parser.parseArgv(args)

        // then
        val expected = BatchResult(
            controls = listOf(
                top(PROMPT, FLAG, "hi"),
                top(STRATEGY, FLAG, "window", listOf(sub(STRATEGY, KEEP_LAST, "8"))),
            ),
            errors = emptyList(),
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `when a free-text value has spaces in one argv slot - then it stays one value`() {
        // given — the shell already made "do x and y" a single arg
        val args = listOf("-prompt", "do x and y")

        // when
        val actual = parser.parseArgv(args)

        // then
        assertEquals(BatchResult(listOf(top(PROMPT, FLAG, "do x and y")), emptyList()), actual)
    }
    //endregion

    //region dash-prefixed values (arity)

    @Test
    fun `when a flag value starts with a dash - then it stays the flag's value`() {
        // given
        val args = listOf("-prompt", "-v")

        // when
        val actual = parser.parseArgv(args)

        // then
        val expected = BatchResult(listOf(top(PROMPT, FLAG, "-v")), emptyList())
        assertEquals(expected, actual)
    }

    @Test
    fun `when a sub value starts with a dash - then it reaches the sub and is validated`() {
        // given
        val args = listOf("-strategy", "window", "keepLast", "-3")

        // when
        val actual = parser.parseArgv(args)

        // then
        val expected = BatchResult(
            controls = emptyList(),
            errors = listOf(ParseError.BadValue(KEEP_LAST, "-3", "an integer >= 0")),
        )
        assertEquals(expected, actual)
    }
    //endregion

    //region catalog integrity

    @Test
    fun `when inspecting subcommands - then every parent chain roots at a top-level control`() {
        // given
        val topArgs = CliControls.all.filter { it.isTopLevel }.map { it.arg }.toSet()

        // when - then
        CliControls.all.filter { !it.isTopLevel }.forEach { spec ->
            val root = spec.parent!!.first()
            assertTrue(root in topArgs, "sub '${spec.token}' roots at '$root' which has no top-level control")
        }
    }

    @Test
    fun `when inspecting top-level controls - then each arg appears once so lookup is deterministic`() {
        // when
        val dups = CliControls.all.filter { it.isTopLevel }.groupBy { it.arg }.filterValues { it.size > 1 }

        // then
        assertTrue(dups.isEmpty(), "duplicate top-level args: ${dups.keys}")
    }
    //endregion
}

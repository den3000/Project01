package ru.den.writes.code.agenticHub.cliJvm.cliargs

import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.KEEP_LAST
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.PROMPT
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.STRATEGY
import ru.den.writes.code.agenticHub.cliJvm.cliargs.Surface.FLAG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Catalog meta-invariants (structure of [CliArgs.all] — not grammar) plus the
 * argv-splitting mechanic of [CliArgsParser.parseArgv]: one argv splits into
 * one control group per `-flag`. Per-control grammar lives in the grammar suite.
 */
class CliArgsCatalogTest {

    private val parser = CliArgsParser()

    //region argv splitting
    @Test
    fun `when an argv carries several controls - then each parses into its own group`() {
        // when
        val actual = parser.parseArgv("-prompt hi -strategy window keepLast 8".toArgsList())

        // then
        assertEquals(
            BatchResult(
                controls = listOf(
                    top(PROMPT, FLAG, "hi"),
                    top(STRATEGY, FLAG, "window", listOf(sub(STRATEGY, KEEP_LAST, "8"))),
                ),
                errors = emptyList(),
            ),
            actual,
        )
    }
    //endregion

    //region catalog integrity
    @Test
    fun `when inspecting subcommands - then every parent chain roots at a top-level control`() {
        // given
        val topArgs = CliArgs.all.filter { it.isTopLevel }.map { it.arg }.toSet()

        // when - then
        CliArgs.all.filter { !it.isTopLevel }.forEach { spec ->
            val root = spec.parent!!.first()
            assertTrue(root in topArgs, "sub '${spec.token}' roots at '$root' which has no top-level control")
        }
    }

    @Test
    fun `when inspecting top-level controls - then each arg appears once so lookup is deterministic`() {
        // when
        val dups = CliArgs.all.filter { it.isTopLevel }.groupBy { it.arg }.filterValues { it.size > 1 }

        // then
        assertTrue(dups.isEmpty(), "duplicate top-level args: ${dups.keys}")
    }
    //endregion
}

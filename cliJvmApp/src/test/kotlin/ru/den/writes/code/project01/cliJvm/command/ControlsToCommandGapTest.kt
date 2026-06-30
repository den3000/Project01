package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.CliArgsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ControlsToCommand: new-grammar productions with no legacy target, and the parse-error bridge. */
class ControlsToCommandGapTest {

    @Test
    fun `when a new-grammar-only op has no legacy target - then InvalidArgumentValue not expressible`() {
        // given — task note, per-entity show, rule list have no CliCommand target; wrong-order is verb-then-name only
        val parser = createCommandsParser()
        val cases = listOf(
            "-task auth note \"did x\"",
            "-session show",
            "-rule",
            "-profile kotlin-senior show",
        )

        // when - then — one invariant over an extending list (rule §11.E)
        cases.forEach { input ->
            val ex = assertFailsWith<CliArgsException.InvalidArgumentValue>(input) { parser.parse(input.toArgsArray()) }
            assertTrue(ex.expectedType.contains("not expressible"), "$input: ${ex.expectedType}")
        }
    }

    @Test
    fun `when the argv is invalid - then the parse error maps to a CliArgsException`() {
        // given
        val parser = createCommandsParser()
        val input = "-strategy bogus"

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> { parser.parse(input.toArgsArray()) }

        // then
        assertEquals("bogus", ex.rawValue)
    }
}

package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.CliArgsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** CliControls front: new-grammar productions with no legacy target, and the parse-error bridge. */
class CliControlsCommandParserGapTest {

    private val parser = CliControlsCommandParser(
        ApiKeys(DUMMY_GEMINI_KEY, DUMMY_OPENROUTER_KEY, DUMMY_HUGGINGFACE_KEY),
    )

    @Test
    fun `when a new-grammar-only op has no legacy target - then InvalidArgumentValue not expressible`() {
        // given — task note, per-entity show, rule list have no CliCommand target
        // (per-session clear now maps to CleanSession; rule/task/profile clear are wired)
        val cases = listOf(
            arrayOf("-task", "auth", "note", "did x"),
            arrayOf("-session", "show"),
            arrayOf("-rule"),
            arrayOf("-profile", "kotlin-senior", "show"),  // wrong order — verb-then-name only
        )

        // when - then — one invariant over an extending list (rule §11.E)
        cases.forEach { args ->
            val ex = assertFailsWith<CliArgsException.InvalidArgumentValue>(args.joinToString(" ")) { parser.parse(args) }
            assertTrue(ex.expectedType.contains("not expressible"), "${args.joinToString(" ")}: ${ex.expectedType}")
        }
    }

    @Test
    fun `when the argv is invalid - then the parse error maps to a CliArgsException`() {
        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parser.parse(arrayOf("-strategy", "bogus"))
        }

        // then
        assertEquals("bogus", ex.rawValue)
    }

    private companion object {
        const val DUMMY_GEMINI_KEY = "test-gemini-key"
        const val DUMMY_OPENROUTER_KEY = "test-openrouter-key"
        const val DUMMY_HUGGINGFACE_KEY = "test-huggingface-key"
    }
}

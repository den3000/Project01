package ru.den.writes.code.project01.cliJvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliArgsTest {

    // --- mode selection: admin / chat / oneshot ----------------------

    @Test
    fun `dash sessions returns ListSessions object`() {
        val parsed = CliArgs.from(arrayOf("-sessions"))
        assertEquals(CliArgs.ListSessions, parsed)
    }

    @Test
    fun `dash sessions wins over prompt`() {
        // Documented behaviour: in list mode every other flag is ignored.
        val parsed = CliArgs.from(arrayOf("-sessions", "-prompt", "ignored"))
        assertEquals(CliArgs.ListSessions, parsed)
    }

    @Test
    fun `dash clean returns Clean object`() {
        val parsed = CliArgs.from(arrayOf("-clean"))
        assertEquals(CliArgs.Clean, parsed)
    }

    @Test
    fun `bare prompt becomes Chat with everything else null`() {
        val parsed = CliArgs.from(arrayOf("-prompt", "hi"))
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals("hi", chat.prompt)
        assertNull(chat.maxTokens)
        assertNull(chat.stopSequences)
        assertNull(chat.endSequence)
        assertNull(chat.temperature)
        assertNull(chat.model)
        assertNull(chat.session)
    }

    @Test
    fun `Chat carries all knobs when present`() {
        val parsed = CliArgs.from(
            arrayOf(
                "-prompt", "hi",
                "-session", "foo",
                "-maxTokens", "100",
                "-temperature", "0.7",
                "-stopSequence", "stop", "halt",
                "-endSequence", "[END]",
                "-model", "gemini-2.5-flash",
            )
        )
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals("hi", chat.prompt)
        assertEquals(100, chat.maxTokens)
        assertEquals(0.7, chat.temperature)
        assertEquals(listOf("stop", "halt"), chat.stopSequences)
        assertEquals("[END]", chat.endSequence)
        assertEquals("gemini-2.5-flash", chat.model)
        assertEquals("foo", chat.session)
    }

    @Test
    fun `multi-word prompt joined with spaces`() {
        val parsed = CliArgs.from(arrayOf("-prompt", "tell", "me", "a", "joke"))
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals("tell me a joke", chat.prompt)
    }

    @Test
    fun `dash oneshot returns OneShot variant`() {
        val parsed = CliArgs.from(arrayOf("-prompt", "hi", "-oneshot"))
        val oneShot = assertIs<CliArgs.OneShot>(parsed)
        assertEquals("hi", oneShot.prompt)
    }

    @Test
    fun `OneShot carries generation knobs`() {
        val parsed = CliArgs.from(
            arrayOf(
                "-prompt", "hi",
                "-oneshot",
                "-maxTokens", "42",
                "-temperature", "0.5",
                "-model", "gemini-2.5-flash",
            )
        )
        val oneShot = assertIs<CliArgs.OneShot>(parsed)
        assertEquals(42, oneShot.maxTokens)
        assertEquals(0.5, oneShot.temperature)
        assertEquals("gemini-2.5-flash", oneShot.model)
    }

    // --- mode conflicts and validation errors ------------------------

    @Test
    fun `oneshot rejects session`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            CliArgs.from(arrayOf("-prompt", "hi", "-oneshot", "-session", "foo"))
        }
        assertEquals("-session", ex.argName)
    }

    @Test
    fun `oneshot without prompt is missing required argument`() {
        val ex = assertFailsWith<CliArgsException.MissingRequiredArgument> {
            CliArgs.from(arrayOf("-oneshot"))
        }
        assertEquals("-prompt", ex.argName)
    }

    @Test
    fun `sessions and clean together is rejected`() {
        assertFailsWith<CliArgsException.InvalidArgumentValue> {
            CliArgs.from(arrayOf("-sessions", "-clean"))
        }
    }

    @Test
    fun `sessions and oneshot together is rejected`() {
        assertFailsWith<CliArgsException.InvalidArgumentValue> {
            CliArgs.from(arrayOf("-sessions", "-oneshot", "-prompt", "x"))
        }
    }

    @Test
    fun `clean and oneshot together is rejected`() {
        assertFailsWith<CliArgsException.InvalidArgumentValue> {
            CliArgs.from(arrayOf("-clean", "-oneshot", "-prompt", "x"))
        }
    }

    @Test
    fun `non-integer maxTokens rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            CliArgs.from(arrayOf("-prompt", "hi", "-maxTokens", "abc"))
        }
        assertEquals("-maxTokens", ex.argName)
    }

    @Test
    fun `non-decimal temperature rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            CliArgs.from(arrayOf("-prompt", "hi", "-temperature", "hot"))
        }
        assertEquals("-temperature", ex.argName)
    }

    @Test
    fun `too many stop sequences rejected`() {
        val ex = assertFailsWith<CliArgsException.TooManyValues> {
            CliArgs.from(
                arrayOf("-prompt", "hi", "-stopSequence", "a", "b", "c", "d", "e", "f")
            )
        }
        assertEquals("-stopSequence", ex.argName)
        assertEquals(6, ex.count)
        assertEquals(CliArgs.MAX_STOP_SEQUENCES, ex.maxAllowed)
    }

    @Test
    fun `session name with whitespace is rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            CliArgs.from(arrayOf("-prompt", "hi", "-session", "bad", "name", "with", "spaces"))
        }
        assertEquals("-session", ex.argName)
    }

    @Test
    fun `session name longer than 64 chars is rejected`() {
        val longName = "a".repeat(65)
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            CliArgs.from(arrayOf("-prompt", "hi", "-session", longName))
        }
        assertEquals("-session", ex.argName)
    }

    @Test
    fun `prompt missing in default mode is missing required argument`() {
        val ex = assertFailsWith<CliArgsException.MissingRequiredArgument> {
            CliArgs.from(arrayOf<String>())
        }
        assertEquals("-prompt", ex.argName)
    }

    // --- USAGE smoke check ------------------------------------------

    @Test
    fun `USAGE mentions every public flag`() {
        // Light coverage so a future flag-renaming doesn't silently
        // leave USAGE stale.
        val usage = CliArgs.USAGE
        listOf(
            "-prompt", "-maxTokens", "-stopSequence", "-endSequence",
            "-temperature", "-model", "-session", "-oneshot",
            "-sessions", "-clean",
        ).forEach { flag ->
            assertTrue(usage.contains(flag), "USAGE missing mention of $flag")
        }
    }
}

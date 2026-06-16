package ru.den.writes.code.project01.cliJvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GeminiApiTest {

    // --- redactGeminiKey --------------------------------------------

    @Test
    fun `redactGeminiKey hides the key value in a URL`() {
        val input = "Request timeout has expired [url=https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key=AIzaSyDXX7kBs7yJIUONgVy0Rrw6CsQLa5RKqTo, request_timeout=120000 ms]"
        val expected = "Request timeout has expired [url=https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key=***, request_timeout=120000 ms]"
        assertEquals(expected, redactGeminiKey(input))
    }

    @Test
    fun `redactGeminiKey leaves untouched text alone`() {
        assertEquals("no key in here", redactGeminiKey("no key in here"))
    }

    @Test
    fun `redactGeminiKey on null returns empty string`() {
        // Exceptions can carry null messages; the redactor coerces those
        // to "" so we can interpolate without a NullPointerException.
        assertEquals("", redactGeminiKey(null))
    }

    // --- parseRetryAfterMillis --------------------------------------

    @Test
    fun `parseRetryAfterMillis picks up the Gemini hint`() {
        val body = """{"error":{"message":"You exceeded your current quota. Please retry in 3.307306781s.","status":"RESOURCE_EXHAUSTED"}}"""
        assertEquals(3307L, parseRetryAfterMillis(body))
    }

    @Test
    fun `parseRetryAfterMillis returns null when no hint is present`() {
        assertNull(parseRetryAfterMillis("""{"error":{"message":"Some other failure"}}"""))
    }

    @Test
    fun `parseRetryAfterMillis handles integer seconds`() {
        assertEquals(5_000L, parseRetryAfterMillis("retry in 5s, please"))
    }

    // --- buildSystemInstruction -------------------------------------

    @Test
    fun `buildSystemInstruction returns null when there is no SYSTEM input and no endSequence`() {
        // Regression guard: no-memory + no-endSequence callers should
        // hit the same `systemInstruction = null` path they did before.
        assertNull(buildSystemInstruction(emptyList(), endSequence = null))
    }

    @Test
    fun `buildSystemInstruction with only endSequence reproduces the prior behaviour`() {
        val si = buildSystemInstruction(emptyList(), endSequence = "<<DONE>>")
        assertNotNull(si)
        val text = si.parts.single().text
        assertEquals("Always end your response with the literal text: \"<<DONE>>\"", text)
    }

    @Test
    fun `buildSystemInstruction with only SYSTEM messages joins them with blank-line separator`() {
        val si = buildSystemInstruction(
            listOf(
                Message(Role.SYSTEM, "[Profile]\nP"),
                Message(Role.SYSTEM, "[Rules]\n- R1"),
            ),
            endSequence = null,
        )
        assertNotNull(si)
        val text = si.parts.single().text
        assertEquals("[Profile]\nP\n\n[Rules]\n- R1", text)
    }

    @Test
    fun `buildSystemInstruction appends endSequence after SYSTEM messages with a blank line`() {
        val si = buildSystemInstruction(
            listOf(Message(Role.SYSTEM, "[Profile]\nP")),
            endSequence = "<<DONE>>",
        )
        assertNotNull(si)
        val text = si.parts.single().text
        assertEquals(
            "[Profile]\nP\n\nAlways end your response with the literal text: \"<<DONE>>\"",
            text,
        )
    }
}

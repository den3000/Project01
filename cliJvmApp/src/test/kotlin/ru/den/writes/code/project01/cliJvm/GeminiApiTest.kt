package ru.den.writes.code.project01.cliJvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GeminiApiTest {

    //region redactGeminiKey

    @Test
    fun `when redactGeminiKey called with URL containing key - then key value replaced with stars`() {
        // given
        val input = "Request timeout has expired [url=https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key=AIzaSyDXX7kBs7yJIUONgVy0Rrw6CsQLa5RKqTo, request_timeout=120000 ms]"

        // when
        val actual = redactGeminiKey(input)

        // then
        val expected = "Request timeout has expired [url=https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key=***, request_timeout=120000 ms]"
        assertEquals(expected, actual)
    }

    @Test
    fun `when redactGeminiKey called with text without key - then text returned unchanged`() {
        // given
        val input = "no key in here"

        // when
        val actual = redactGeminiKey(input)

        // then
        val expected = "no key in here"
        assertEquals(expected, actual)
    }

    @Test
    fun `when redactGeminiKey called with null - then empty string returned`() {
        // given
        // Exceptions can carry null messages; the redactor coerces those
        // to "" so we can interpolate without a NullPointerException.
        val input: String? = null

        // when
        val actual = redactGeminiKey(input)

        // then
        val expected = ""
        assertEquals(expected, actual)
    }
    //endregion

    //region parseRetryAfterMillis

    @Test
    fun `when parseRetryAfterMillis called with Gemini retry hint - then milliseconds extracted`() {
        // given
        val body = """{"error":{"message":"You exceeded your current quota. Please retry in 3.307306781s.","status":"RESOURCE_EXHAUSTED"}}"""

        // when
        val actual = parseRetryAfterMillis(body)

        // then
        val expected = 3307L
        assertEquals(expected, actual)
    }

    @Test
    fun `when parseRetryAfterMillis called without retry hint - then null returned`() {
        // given
        val body = """{"error":{"message":"Some other failure"}}"""

        // when
        val actual = parseRetryAfterMillis(body)

        // then
        assertNull(actual)
    }

    @Test
    fun `when parseRetryAfterMillis called with integer seconds - then converted to milliseconds`() {
        // given
        val body = "retry in 5s, please"

        // when
        val actual = parseRetryAfterMillis(body)

        // then
        val expected = 5_000L
        assertEquals(expected, actual)
    }
    //endregion

    //region buildSystemInstruction

    @Test
    fun `when buildSystemInstruction called with no SYSTEM input and no endSequence - then null returned`() {
        // given
        // Regression guard: no-memory + no-endSequence callers should
        // hit the same `systemInstruction = null` path they did before.
        val messages = emptyList<Message>()

        // when
        val actual = buildSystemInstruction(messages, endSequence = null)

        // then
        assertNull(actual)
    }

    @Test
    fun `when buildSystemInstruction called with only endSequence - then prior behaviour reproduced`() {
        // given
        val messages = emptyList<Message>()
        val endSequence = "<<DONE>>"

        // when
        val si = buildSystemInstruction(messages, endSequence = endSequence)

        // then
        assertNotNull(si)
        val expected = "Always end your response with the literal text: \"<<DONE>>\""
        assertEquals(expected, si.parts.single().text)
    }

    @Test
    fun `when buildSystemInstruction called with multiple SYSTEM messages - then joined with blank-line separator`() {
        // given
        val messages = listOf(
            Message(Role.SYSTEM, "[Profile]\nP"),
            Message(Role.SYSTEM, "[Rules]\n- R1"),
        )

        // when
        val si = buildSystemInstruction(messages, endSequence = null)

        // then
        assertNotNull(si)
        val expected = "[Profile]\nP\n\n[Rules]\n- R1"
        assertEquals(expected, si.parts.single().text)
    }

    @Test
    fun `when buildSystemInstruction called with SYSTEM messages plus endSequence - then endSequence appended after blank line`() {
        // given
        val messages = listOf(Message(Role.SYSTEM, "[Profile]\nP"))
        val endSequence = "<<DONE>>"

        // when
        val si = buildSystemInstruction(messages, endSequence = endSequence)

        // then
        assertNotNull(si)
        val expected = "[Profile]\nP\n\nAlways end your response with the literal text: \"<<DONE>>\""
        assertEquals(expected, si.parts.single().text)
    }
    //endregion
}

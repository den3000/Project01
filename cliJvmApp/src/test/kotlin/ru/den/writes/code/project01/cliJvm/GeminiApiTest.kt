package ru.den.writes.code.project01.cliJvm

import kotlin.test.Test
import kotlin.test.assertEquals
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
}

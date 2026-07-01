package ru.den.writes.code.project01.shared.llm.huggingface

import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HuggingFaceApiTest {

    //region parseRetryAfterSeconds

    @Test
    fun `when parseRetryAfterSeconds called with integer header - then seconds returned`() {
        // given
        val header = "3"

        // when
        val actual = parseRetryAfterSeconds(header)

        // then
        val expected = 3L
        assertEquals(expected, actual)
    }

    @Test
    fun `when parseRetryAfterSeconds called with whitespace-padded value - then trimmed value returned`() {
        // given
        val header = "  7  "

        // when
        val actual = parseRetryAfterSeconds(header)

        // then
        val expected = 7L
        assertEquals(expected, actual)
    }

    @Test
    fun `when parseRetryAfterSeconds called with null header - then null returned`() {
        // given
        val header: String? = null

        // when
        val actual = parseRetryAfterSeconds(header)

        // then
        assertNull(actual)
    }

    @Test
    fun `when parseRetryAfterSeconds called with unparseable value - then null returned`() {
        // given
        // HTTP-date form is allowed by RFC 7231 but not parsed here;
        // callers fall back to the default backoff.
        val header = "Wed, 21 Oct 2026 07:28:00 GMT"

        // when
        val actual = parseRetryAfterSeconds(header)

        // then
        assertNull(actual)
    }

    @Test
    fun `when parseRetryAfterSeconds called with negative value - then clamped to zero`() {
        // given
        val header = "-5"

        // when
        val actual = parseRetryAfterSeconds(header)

        // then
        val expected = 0L
        assertEquals(expected, actual)
    }
    //endregion

    //region HuggingFaceUsage.toNeutral

    @Test
    fun `when toNeutral called with reasoning tokens - then folded into thoughtsTokens`() {
        // given
        val usage = HuggingFaceUsage(
            promptTokens = 120,
            completionTokens = 200,
            totalTokens = 320,
            completionTokensDetails = CompletionTokensDetails(reasoningTokens = 150),
        )

        // when
        val actual = usage.toNeutral()

        // then
        // reasoning is subtracted from completion so outputTokens stays
        // the visible-text-only count — same split GeminiApi uses.
        assertEquals(120, actual.promptTokens)
        assertEquals(50, actual.outputTokens)
        assertEquals(150, actual.thoughtsTokens)
        assertEquals(320, actual.totalTokens)
    }

    @Test
    fun `when toNeutral called without details - then thoughtsTokens stays zero`() {
        // given
        val usage = HuggingFaceUsage(
            promptTokens = 30,
            completionTokens = 45,
            totalTokens = 75,
            completionTokensDetails = null,
        )

        // when
        val actual = usage.toNeutral()

        // then
        assertEquals(30, actual.promptTokens)
        assertEquals(45, actual.outputTokens)
        assertEquals(0, actual.thoughtsTokens)
        assertEquals(75, actual.totalTokens)
    }

    @Test
    fun `when reasoning exceeds completion - then outputTokens clamped to zero`() {
        // given
        // Pathological case some providers have shipped — completion is
        // smaller than the reported reasoning count. Guard against a
        // negative outputTokens reaching the footer / pricing.
        val usage = HuggingFaceUsage(
            promptTokens = 10,
            completionTokens = 5,
            totalTokens = 15,
            completionTokensDetails = CompletionTokensDetails(reasoningTokens = 8),
        )

        // when
        val actual = usage.toNeutral()

        // then
        assertEquals(0, actual.outputTokens)
        assertEquals(8, actual.thoughtsTokens)
    }
    //endregion

    //region HuggingFaceResponse deserialization

    @Test
    fun `when response carries reasoning_tokens - then parsed through completionTokensDetails`() {
        // given
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val raw = """
            {
              "choices":[{"message":{"role":"assistant","content":"42"}}],
              "usage":{
                "prompt_tokens": 10,
                "completion_tokens": 25,
                "total_tokens": 35,
                "completion_tokens_details": { "reasoning_tokens": 18 }
              }
            }
        """.trimIndent()

        // when
        val resp = json.decodeFromString<HuggingFaceResponse>(raw)

        // then
        assertEquals(18, resp.usage?.completionTokensDetails?.reasoningTokens)
        // And the neutral mapping is what pricing/footer ultimately see.
        val neutral = resp.usage!!.toNeutral()
        assertEquals(10, neutral.promptTokens)
        assertEquals(7, neutral.outputTokens)
        assertEquals(18, neutral.thoughtsTokens)
    }

    @Test
    fun `when response has no completion_tokens_details - then still parses with thoughtsTokens zero`() {
        // given
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val raw = """
            {
              "choices":[{"message":{"role":"assistant","content":"hi"}}],
              "usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}
            }
        """.trimIndent()

        // when
        val resp = json.decodeFromString<HuggingFaceResponse>(raw)

        // then
        assertNull(resp.usage?.completionTokensDetails)
        assertEquals(0, resp.usage!!.toNeutral().thoughtsTokens)
    }
    //endregion

    //region buildHuggingFaceWireMessages

    @Test
    fun `when buildHuggingFaceWireMessages called without SYSTEM input or endSequence - then no system message emitted`() {
        // given
        val messages = listOf(
            Message(Role.USER, "hi"),
            Message(Role.ASSISTANT, "hello"),
        )

        // when
        val wire = buildHuggingFaceWireMessages(messages, endSequence = null)

        // then
        assertEquals(2, wire.size)
        assertEquals("user", wire[0].role)
        assertEquals("assistant", wire[1].role)
    }

    @Test
    fun `when buildHuggingFaceWireMessages called with only endSequence - then one system message at the head`() {
        // given
        val messages = listOf(Message(Role.USER, "hi"))
        val endSequence = "<<DONE>>"

        // when
        val wire = buildHuggingFaceWireMessages(messages, endSequence = endSequence)

        // then
        assertEquals(2, wire.size)
        assertEquals("system", wire[0].role)
        val expected = "Always end your response with the literal text: \"<<DONE>>\""
        assertEquals(expected, wire[0].content)
    }

    @Test
    fun `when buildHuggingFaceWireMessages called with multiple SYSTEM messages - then collapsed into one`() {
        // given
        val messages = listOf(
            Message(Role.SYSTEM, "[Profile]\nP"),
            Message(Role.SYSTEM, "[Rules]\n- R1"),
            Message(Role.USER, "hi"),
        )

        // when
        val wire = buildHuggingFaceWireMessages(messages, endSequence = null)

        // then
        assertEquals(2, wire.size)
        assertEquals("system", wire[0].role)
        assertEquals("[Profile]\nP\n\n[Rules]\n- R1", wire[0].content)
    }

    @Test
    fun `when buildHuggingFaceWireMessages called with SYSTEM messages plus endSequence - then endSequence merged into system`() {
        // given
        val messages = listOf(
            Message(Role.SYSTEM, "[Profile]\nP"),
            Message(Role.USER, "hi"),
        )
        val endSequence = "<<DONE>>"

        // when
        val wire = buildHuggingFaceWireMessages(messages, endSequence = endSequence)

        // then
        assertEquals(2, wire.size)
        val expected = "[Profile]\nP\n\nAlways end your response with the literal text: \"<<DONE>>\""
        assertEquals(expected, wire[0].content)
    }

    @Test
    fun `when SYSTEM messages interleaved with USER and ASSISTANT - then all SYSTEM collected at head`() {
        // given
        val messages = listOf(
            Message(Role.USER, "hello"),
            Message(Role.SYSTEM, "[Rules]\n- R1"),
            Message(Role.ASSISTANT, "world"),
        )

        // when
        val wire = buildHuggingFaceWireMessages(messages, endSequence = null)

        // then
        assertEquals(3, wire.size)
        assertEquals("system", wire[0].role)
        assertEquals("[Rules]\n- R1", wire[0].content)
        assertEquals("user", wire[1].role)
        assertEquals("assistant", wire[2].role)
    }
    //endregion
}

package ru.den.writes.code.project01.cliJvm

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HuggingFaceApiTest {

    // --- parseRetryAfterSeconds -------------------------------------

    @Test
    fun `parseRetryAfterSeconds picks up an integer value`() {
        assertEquals(3L, parseRetryAfterSeconds("3"))
    }

    @Test
    fun `parseRetryAfterSeconds trims whitespace around the value`() {
        assertEquals(7L, parseRetryAfterSeconds("  7  "))
    }

    @Test
    fun `parseRetryAfterSeconds returns null on a missing header`() {
        assertNull(parseRetryAfterSeconds(null))
    }

    @Test
    fun `parseRetryAfterSeconds returns null on an unparseable value`() {
        // HTTP-date form is allowed by RFC 7231 but not parsed here;
        // callers fall back to the default backoff.
        assertNull(parseRetryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun `parseRetryAfterSeconds clamps negative values to zero`() {
        assertEquals(0L, parseRetryAfterSeconds("-5"))
    }

    // --- HuggingFaceUsage.toNeutral ---------------------------------

    @Test
    fun `toNeutral folds reasoning tokens into thoughtsTokens`() {
        val usage = HuggingFaceUsage(
            promptTokens = 120,
            completionTokens = 200,
            totalTokens = 320,
            completionTokensDetails = CompletionTokensDetails(reasoningTokens = 150),
        )
        val neutral = usage.toNeutral()
        // reasoning is subtracted from completion so outputTokens stays
        // the visible-text-only count — same split GeminiApi uses.
        assertEquals(120, neutral.promptTokens)
        assertEquals(50, neutral.outputTokens)
        assertEquals(150, neutral.thoughtsTokens)
        assertEquals(320, neutral.totalTokens)
    }

    @Test
    fun `toNeutral leaves thoughtsTokens at zero when details are absent`() {
        val usage = HuggingFaceUsage(
            promptTokens = 30,
            completionTokens = 45,
            totalTokens = 75,
            completionTokensDetails = null,
        )
        val neutral = usage.toNeutral()
        assertEquals(30, neutral.promptTokens)
        assertEquals(45, neutral.outputTokens)
        assertEquals(0, neutral.thoughtsTokens)
        assertEquals(75, neutral.totalTokens)
    }

    @Test
    fun `toNeutral clamps outputTokens to zero when reasoning exceeds completion`() {
        // Pathological case some providers have shipped — completion is
        // smaller than the reported reasoning count. Guard against a
        // negative outputTokens reaching the footer / pricing.
        val usage = HuggingFaceUsage(
            promptTokens = 10,
            completionTokens = 5,
            totalTokens = 15,
            completionTokensDetails = CompletionTokensDetails(reasoningTokens = 8),
        )
        val neutral = usage.toNeutral()
        assertEquals(0, neutral.outputTokens)
        assertEquals(8, neutral.thoughtsTokens)
    }

    // --- HuggingFaceResponse deserialization -------------------------

    @Test
    fun `response with reasoning_tokens parses through completionTokensDetails`() {
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
        val resp = json.decodeFromString<HuggingFaceResponse>(raw)
        assertEquals(18, resp.usage?.completionTokensDetails?.reasoningTokens)
        // And the neutral mapping is what pricing/footer ultimately see.
        val neutral = resp.usage!!.toNeutral()
        assertEquals(10, neutral.promptTokens)
        assertEquals(7, neutral.outputTokens)
        assertEquals(18, neutral.thoughtsTokens)
    }

    @Test
    fun `response without completion_tokens_details still parses`() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val raw = """
            {
              "choices":[{"message":{"role":"assistant","content":"hi"}}],
              "usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}
            }
        """.trimIndent()
        val resp = json.decodeFromString<HuggingFaceResponse>(raw)
        assertNull(resp.usage?.completionTokensDetails)
        assertEquals(0, resp.usage!!.toNeutral().thoughtsTokens)
    }
}

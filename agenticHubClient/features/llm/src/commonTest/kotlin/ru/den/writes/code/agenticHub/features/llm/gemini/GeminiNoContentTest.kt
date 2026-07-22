package ru.den.writes.code.agenticHub.features.llm.gemini

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * When an answerless Gemini reply becomes a failure instead of a crash.
 *
 * This is a contract: a response with neither text nor a tool call surfaces as
 * `LlmResult.error`, not as an empty answer or a thrown MissingFieldException, and the
 * message names `finishReason` so the cause is actionable. Every caller reads that field,
 * so the boundary is pinned here.
 */
class GeminiNoContentTest {

    //region a reply that carries nothing
    @Test
    fun `when the reply holds no candidates at all - then that is what the error names`() {
        // given
        val response = GeminiResponse(candidates = emptyList())

        // when
        val actual = response.noContentError()

        // then
        assertEquals("Gemini returned no content (finishReason=no candidates)", actual)
    }

    @Test
    fun `when a candidate stopped without content - then every finish reason is surfaced`() {
        // given
        val reasons = listOf("MAX_TOKENS", "SAFETY", "RECITATION", "MALFORMED_FUNCTION_CALL")

        // when - then
        reasons.forEach { reason ->
            val response = GeminiResponse(candidates = listOf(candidate(finishReason = reason)))
            assertEquals(
                "Gemini returned no content (finishReason=$reason)",
                response.noContentError(),
                "finishReason=$reason",
            )
        }
    }

    @Test
    fun `when a candidate carries content without parts - then it counts as empty, not a crash`() {
        // given — the exact shape that used to throw MissingFieldException: content present,
        // parts absent (Gemini stopped with no output)
        val response = GeminiResponse(candidates = listOf(Candidate(content = Content(), finishReason = "MAX_TOKENS")))

        // when
        val actual = response.noContentError()

        // then
        assertEquals("Gemini returned no content (finishReason=MAX_TOKENS)", actual)
    }

    @Test
    fun `when a candidate has parts but none of them carry text - then it still counts as empty`() {
        // given
        val response = GeminiResponse(
            candidates = listOf(candidate(parts = listOf(Part(text = "   ")), finishReason = "STOP")),
        )

        // when
        val actual = response.noContentError()

        // then
        assertEquals("Gemini returned no content (finishReason=STOP)", actual)
    }
    //endregion

    //region a reply that carries something
    @Test
    fun `when the candidate carries text - then there is no error`() {
        // given
        val response = GeminiResponse(
            candidates = listOf(candidate(parts = listOf(Part(text = "ответ")), finishReason = "STOP")),
        )

        // when
        val actual = response.noContentError()

        // then
        assertNull(actual)
    }

    @Test
    fun `when the candidate carries only tool calls - then there is no error`() {
        // given
        val call = FunctionCall(name = "read_project_file", args = buildJsonObject { put("path", "a.md") })
        val response = GeminiResponse(
            candidates = listOf(candidate(parts = listOf(Part(functionCall = call)), finishReason = "STOP")),
        )

        // when
        val actual = response.noContentError()

        // then
        assertNull(actual, "текстa нет, но вызовы есть — это нормальная середина цикла, а не провал")
    }
    //endregion

    private fun candidate(parts: List<Part> = emptyList(), finishReason: String? = null): Candidate =
        Candidate(content = if (parts.isEmpty()) null else Content(parts = parts), finishReason = finishReason)
}

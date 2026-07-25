package ru.den.writes.code.agenticHub.features.llm.gemini

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import ru.den.writes.code.agenticHub.features.llm.GenerationParams
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which failures [GeminiApi] retries, and how many times it really goes back to the wire.
 *
 * [spendRetryBudget] covers the arithmetic; this covers the loop that spends it — that a
 * status reaches its branch at all. The distinction is not academic: a 503 spike is what
 * costs a long run its minutes, and the counter being right proves nothing if the status
 * never gets there. The transport is canned, so no provider is called and the backoff
 * `delay`s are virtual under `runTest`.
 */
class GeminiApiRetryTest {

    @Test
    fun `when 503 never clears - then the budget is spent and the error names it`() = runTest {
        // given
        val transport = cannedTransport(listOf(Canned.Status(HttpStatusCode.ServiceUnavailable, OVERLOADED_BODY)))

        // when
        val result = transport.api().send(listOf(Message(Role.USER, "hi")), GenerationParams())

        // then
        assertEquals(4, transport.requests, "one attempt plus three retries")
        val error = assertNotNull(result.error)
        assertTrue("503" in error, error)
        assertTrue("after 3 retries" in error, error)
    }

    @Test
    fun `when a 503 spike drains - then the reply comes back and no further request is made`() = runTest {
        // given
        val transport = cannedTransport(
            listOf(
                Canned.Status(HttpStatusCode.ServiceUnavailable, OVERLOADED_BODY),
                Canned.Status(HttpStatusCode.ServiceUnavailable, OVERLOADED_BODY),
                Canned.Status(HttpStatusCode.OK, REPLY_BODY),
            ),
        )

        // when
        val result = transport.api().send(listOf(Message(Role.USER, "hi")), GenerationParams())

        // then
        assertEquals(3, transport.requests)
        assertEquals("ok", result.text)
        assertNull(result.error)
        assertEquals(15, result.usage?.totalTokens)
    }

    @Test
    fun `when 429 keeps coming - then exactly one extra attempt is made`() = runTest {
        // given — a rate limit spends the whole budget at once, unlike a 503
        val transport = cannedTransport(listOf(Canned.Status(HttpStatusCode.TooManyRequests, QUOTA_BODY)))

        // when
        val result = transport.api().send(listOf(Message(Role.USER, "hi")), GenerationParams())

        // then
        assertEquals(2, transport.requests)
        val error = assertNotNull(result.error)
        assertTrue("429" in error, error)
    }

    @Test
    fun `when a candidate comes back with no content - then exactly one extra attempt is made`() = runTest {
        // given
        val transport = cannedTransport(listOf(Canned.Status(HttpStatusCode.OK, NO_CONTENT_BODY)))

        // when
        val result = transport.api().send(listOf(Message(Role.USER, "hi")), GenerationParams())

        // then
        assertEquals(2, transport.requests)
        val error = assertNotNull(result.error)
        assertTrue("no content" in error, error)
        assertTrue("MAX_TOKENS" in error, error)
    }

    @Test
    fun `when the connection itself fails - then nothing is retried`() = runTest {
        // given — not a transient provider state: the session's error path decides what to do
        val transport = cannedTransport(listOf(Canned.Boom))

        // when
        val result = transport.api().send(listOf(Message(Role.USER, "hi")), GenerationParams())

        // then
        assertEquals(1, transport.requests)
        assertNotNull(result.error)
    }

    //region canned transport

    /** One canned outcome for a request: an HTTP reply, or a transport-level failure. */
    private sealed interface Canned {
        data class Status(val status: HttpStatusCode, val body: String) : Canned
        data object Boom : Canned
    }

    /**
     * An [HttpClient] answering from [replies] in order, the last one repeating for good —
     * so "always 503" is a single entry — while counting how often it was asked. Content
     * negotiation is installed because the request body is serialized through it.
     */
    private class CannedTransport(replies: List<Canned>) {
        var requests: Int = 0
            private set

        private val client = HttpClient(
            MockEngine { _ ->
                val reply = replies[minOf(requests, replies.lastIndex)]
                requests++
                when (reply) {
                    is Canned.Status -> respond(
                        content = reply.body,
                        status = reply.status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )

                    Canned.Boom -> throw IllegalStateException("connection reset by peer")
                }
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        fun api(): GeminiApi = GeminiApi(client, apiKey = "test-key")
    }

    private fun cannedTransport(replies: List<Canned>) = CannedTransport(replies)

    //endregion

    private companion object {
        const val OVERLOADED_BODY =
            """{"error":{"code":503,"message":"The model is overloaded. Please try again later.","status":"UNAVAILABLE"}}"""

        const val QUOTA_BODY =
            """{"error":{"code":429,"message":"You exceeded your current quota. Please retry in 1.5s.","status":"RESOURCE_EXHAUSTED"}}"""

        const val REPLY_BODY =
            """{"candidates":[{"content":{"parts":[{"text":"ok"}],"role":"model"},"finishReason":"STOP"}],""" +
                """"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"totalTokenCount":15}}"""

        const val NO_CONTENT_BODY =
            """{"candidates":[{"content":{"role":"model"},"finishReason":"MAX_TOKENS"}],""" +
                """"usageMetadata":{"promptTokenCount":10,"totalTokenCount":10}}"""
    }
}

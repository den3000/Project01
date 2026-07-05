package ru.den.writes.code.agenticHub.features.llm.ollama

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OllamaChatDtoTest {

    @Test
    fun `when a request is serialized - then stream is always on the wire`() {
        // given — stream carries no default, so it survives encodeDefaults=false
        val request = request(stream = false)

        // when
        val actual = wireJson.encodeToString(request)

        // then
        assertTrue("\"stream\":false" in actual, "stream must be on the wire, was: $actual")
    }

    @Test
    fun `when think is set - then it is on the wire`() {
        // given
        val request = request(think = false)

        // when
        val actual = wireJson.encodeToString(request)

        // then
        assertTrue("\"think\":false" in actual, "think=false must be on the wire, was: $actual")
    }

    @Test
    fun `when think is null - then it is omitted`() {
        // given
        val request = request(think = null)

        // when
        val actual = wireJson.encodeToString(request)

        // then
        assertFalse("think" in actual, "null think must be omitted, was: $actual")
    }

    @Test
    fun `when no options are set - then options are omitted`() {
        // given
        val request = request(options = null)

        // when
        val actual = wireJson.encodeToString(request)

        // then
        assertFalse("options" in actual, "null options must be omitted, was: $actual")
    }

    private fun request(
        stream: Boolean = false,
        think: Boolean? = null,
        options: OllamaOptions? = null,
    ): OllamaChatRequest =
        OllamaChatRequest(model = "m", messages = emptyList(), stream = stream, think = think, options = options)

    private companion object {
        // Mirrors platform:network's JSON config (encodeDefaults=false, explicitNulls=false) — the
        // exact settings that made an earlier `stream = false` default silently vanish from the wire.
        val wireJson = Json { encodeDefaults = false; explicitNulls = false }
    }
}

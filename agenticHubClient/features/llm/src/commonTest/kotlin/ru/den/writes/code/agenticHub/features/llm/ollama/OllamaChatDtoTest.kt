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

    @Test
    fun `when num_ctx top_p and seed are set - then they use Ollama snake_case keys on the wire`() {
        // given
        val request = request(options = OllamaOptions(numCtx = 8192, topP = 0.9, seed = 42))

        // when
        val actual = wireJson.encodeToString(request)

        // then
        assertTrue("\"num_ctx\":8192" in actual, "num_ctx must be on the wire, was: $actual")
        assertTrue("\"top_p\":0.9" in actual, "top_p must be on the wire, was: $actual")
        assertTrue("\"seed\":42" in actual, "seed must be on the wire, was: $actual")
    }

    @Test
    fun `when num_ctx top_p and seed are null - then they are omitted`() {
        // given — only temperature set, the new knobs left at their null default
        val request = request(options = OllamaOptions(temperature = 0.0))

        // when
        val actual = wireJson.encodeToString(request)

        // then
        assertFalse("num_ctx" in actual, "null num_ctx must be omitted, was: $actual")
        assertFalse("top_p" in actual, "null top_p must be omitted, was: $actual")
        assertFalse("seed" in actual, "null seed must be omitted, was: $actual")
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

package ru.den.writes.code.agenticHub.features.llm.ollama

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

// Mirrors platform:network's JSON config (the shape that actually goes on the wire):
// encodeDefaults=false, explicitNulls=false. These are the exact settings that made an
// earlier `stream: Boolean = false` default silently vanish — regression-guarded here.
private val wireJson = Json { encodeDefaults = false; explicitNulls = false }

class OllamaChatDtoTest {

    @Test
    fun `stream is always emitted so Ollama does not fall back to NDJSON streaming`() {
        // `stream` carries no default → serialized regardless of encodeDefaults.
        val body = wireJson.encodeToString(
            OllamaChatRequest(model = "m", messages = emptyList(), stream = false),
        )
        assertTrue("\"stream\":false" in body, "stream must be on the wire, was: $body")
    }

    @Test
    fun `think is emitted when set and omitted when null`() {
        val disabled = wireJson.encodeToString(
            OllamaChatRequest(model = "m", messages = emptyList(), stream = false, think = false),
        )
        assertTrue("\"think\":false" in disabled, "think=false must be on the wire, was: $disabled")

        val default = wireJson.encodeToString(
            OllamaChatRequest(model = "m", messages = emptyList(), stream = false, think = null),
        )
        assertTrue("think" !in default, "null think must be omitted, was: $default")
    }

    @Test
    fun `options are omitted entirely when no knobs are set`() {
        val body = wireJson.encodeToString(
            OllamaChatRequest(model = "m", messages = emptyList(), stream = false, options = null),
        )
        assertTrue("options" !in body, "null options must be omitted, was: $body")
    }
}

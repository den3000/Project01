package ru.den.writes.code.agenticHub.features.llm.ollama

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `when tools are declared - then each entry carries the function discriminator on the wire`() {
        // given — type has no default for exactly this reason (encodeDefaults=false drops defaults)
        val request = request(
            tools = listOf(
                OllamaTool(
                    type = "function",
                    function = OllamaFunction(
                        name = "read_project_file",
                        description = "Read a file",
                        parameters = buildJsonObject { put("type", "object") },
                    ),
                ),
            ),
        )

        // when
        val actual = wireJson.encodeToString(request)

        // then
        assertTrue("\"type\":\"function\"" in actual, "the discriminator must be on the wire, was: $actual")
        assertTrue("\"name\":\"read_project_file\"" in actual, "the tool name must be on the wire, was: $actual")
        assertTrue("\"parameters\":{\"type\":\"object\"}" in actual, "the schema must be on the wire, was: $actual")
    }

    @Test
    fun `when no tools are declared - then tools are omitted`() {
        // given
        val request = request(tools = null)

        // when
        val actual = wireJson.encodeToString(request)

        // then
        assertFalse("tools" in actual, "null tools must be omitted, was: $actual")
    }

    @Test
    fun `when a tool turn is serialized - then tool_calls and tool_name use Ollama snake_case keys`() {
        // given
        val request = request(
            messages = listOf(
                OllamaMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(OllamaToolCall(OllamaToolCallFunction(name = "list_files"))),
                ),
                OllamaMessage(role = "tool", content = "a.md", toolName = "list_files"),
            ),
        )

        // when
        val actual = wireJson.encodeToString(request)

        // then
        assertTrue("\"tool_calls\":" in actual, "tool_calls must be on the wire, was: $actual")
        assertTrue("\"tool_name\":\"list_files\"" in actual, "tool_name must be on the wire, was: $actual")
    }

    @Test
    fun `when an ordinary turn is serialized - then no tool fields appear`() {
        // given
        val request = request(messages = listOf(OllamaMessage(role = "user", content = "hi")))

        // when
        val actual = wireJson.encodeToString(request)

        // then
        assertFalse("tool_calls" in actual, "null tool_calls must be omitted, was: $actual")
        assertFalse("tool_name" in actual, "null tool_name must be omitted, was: $actual")
    }

    @Test
    fun `when a reply carries tool_calls - then the name and arguments are parsed`() {
        // given
        val body = """
            {"message":{"role":"assistant","content":"",
             "tool_calls":[{"function":{"name":"read_project_file","arguments":{"path":"README.md"}}}]},
             "done":true}
        """.trimIndent()

        // when
        val actual = wireJson.decodeFromString<OllamaChatResponse>(body)

        // then
        val call = actual.message?.toolCalls?.single()?.function
        assertEquals("read_project_file", call?.name)
        assertEquals(JsonPrimitive("README.md"), call?.argumentsObject()?.get("path"))
    }

    @Test
    fun `when arguments arrive as a JSON string - then they normalize to an object`() {
        // given — some builds hand the argument object back JSON-encoded
        val function = OllamaToolCallFunction(name = "read_project_file", arguments = JsonPrimitive("""{"path":"a.md"}"""))

        // when
        val actual = function.argumentsObject()

        // then
        assertEquals(JsonPrimitive("a.md"), actual["path"])
    }

    @Test
    fun `when arguments are an unparsable string - then they normalize to an empty object`() {
        // given
        val function = OllamaToolCallFunction(name = "read_project_file", arguments = JsonPrimitive("{path:"))

        // when
        val actual = function.argumentsObject()

        // then
        assertEquals(JsonObject(emptyMap()), actual)
    }

    @Test
    fun `when arguments are neither an object nor a string - then they normalize to an empty object`() {
        // given
        val function = OllamaToolCallFunction(name = "read_project_file", arguments = JsonPrimitive(42))

        // when
        val actual = function.argumentsObject()

        // then
        assertEquals(JsonObject(emptyMap()), actual)
    }

    @Test
    fun `when arguments are absent - then they normalize to an empty object`() {
        // given
        val function = OllamaToolCallFunction(name = "read_project_file")

        // when
        val actual = function.argumentsObject()

        // then
        assertEquals(JsonObject(emptyMap()), actual)
    }

    private fun request(
        stream: Boolean = false,
        think: Boolean? = null,
        options: OllamaOptions? = null,
        tools: List<OllamaTool>? = null,
        messages: List<OllamaMessage> = emptyList(),
    ): OllamaChatRequest =
        OllamaChatRequest(
            model = "m",
            messages = messages,
            stream = stream,
            think = think,
            tools = tools,
            options = options,
        )

    private companion object {
        // Mirrors platform:network's JSON config (encodeDefaults=false, explicitNulls=false,
        // ignoreUnknownKeys) — the exact settings that made an earlier `stream = false` default
        // silently vanish from the wire.
        val wireJson = Json { encodeDefaults = false; explicitNulls = false; ignoreUnknownKeys = true }
    }
}

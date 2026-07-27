package ru.den.writes.code.agenticHub.features.llm.ollama

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.den.writes.code.agenticHub.features.llm.ToolDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OllamaFunctionCallTest {

    private val pathSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("path", buildJsonObject { put("type", "string") }) })
    }

    @Test
    fun `when tool definitions are wrapped - then each becomes its own function entry`() {
        // given
        val defs = listOf(
            ToolDefinition("read_project_file", "Read a file", pathSchema),
            ToolDefinition("list_project_files", "List files", pathSchema),
        )

        // when
        val actual = defs.toOllamaTools()

        // then
        assertEquals(listOf("read_project_file", "list_project_files"), actual.map { it.function.name })
        assertTrue(actual.all { it.type == "function" }, "every entry must carry the function discriminator")
        assertEquals("Read a file", actual[0].function.description)
        assertEquals(pathSchema, actual[0].function.parameters)
    }

    @Test
    fun `when a tool definition has no description - then the entry leaves it null`() {
        // given
        val defs = listOf(ToolDefinition("read_project_file", description = null, parameters = pathSchema))

        // when
        val actual = defs.toOllamaTools()

        // then
        assertNull(actual.single().function.description)
    }

    @Test
    fun `when a reply carries no tool calls - then the extracted list is empty`() {
        // given
        val response = response(OllamaRespMessage(role = "assistant", content = "plain answer"))

        // when
        val actual = response.extractToolCalls()

        // then
        assertEquals(emptyList(), actual)
    }

    @Test
    fun `when a reply carries one tool call - then its name and arguments come through`() {
        // given
        val response = response(
            OllamaRespMessage(
                role = "assistant",
                content = "",
                toolCalls = listOf(call("read_project_file", buildJsonObject { put("path", "README.md") })),
            ),
        )

        // when
        val actual = response.extractToolCalls()

        // then
        assertEquals("read_project_file", actual.single().name)
        assertEquals(JsonPrimitive("README.md"), actual.single().arguments["path"])
    }

    @Test
    fun `when a reply carries several tool calls - then all of them keep their order`() {
        // given
        val response = response(
            OllamaRespMessage(
                role = "assistant",
                content = "",
                toolCalls = listOf(
                    call("list_project_files", buildJsonObject { put("dir", "docs") }),
                    call("read_project_file", buildJsonObject { put("path", "docs/a.md") }),
                ),
            ),
        )

        // when
        val actual = response.extractToolCalls()

        // then
        assertEquals(listOf("list_project_files", "read_project_file"), actual.map { it.name })
    }

    @Test
    fun `when a call carries string-encoded arguments - then they arrive as an object`() {
        // given — the tolerant form some builds send instead of a nested object
        val response = response(
            OllamaRespMessage(
                role = "assistant",
                content = "",
                toolCalls = listOf(
                    OllamaToolCall(
                        OllamaToolCallFunction(
                            name = "read_project_file",
                            arguments = JsonPrimitive("""{"path":"README.md"}"""),
                        ),
                    ),
                ),
            ),
        )

        // when
        val actual = response.extractToolCalls()

        // then
        assertEquals(JsonPrimitive("README.md"), actual.single().arguments["path"])
    }

    private fun call(name: String, arguments: JsonObject): OllamaToolCall =
        OllamaToolCall(OllamaToolCallFunction(name = name, arguments = arguments))

    private fun response(message: OllamaRespMessage): OllamaChatResponse =
        OllamaChatResponse(message = message, done = true)
}

package ru.den.writes.code.project01.shared.llm.gemini

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.llm.ToolCall
import ru.den.writes.code.project01.shared.llm.ToolDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeminiFunctionCallTest {

    private val weatherSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("city", buildJsonObject { put("type", "string") }) })
    }

    @Test
    fun `when tool definitions wrapped - then one tool entry holds all declarations`() {
        // given
        val defs = listOf(ToolDefinition("current_weather", "Get current weather", weatherSchema))

        // when
        val tools = defs.toGeminiTools()

        // then
        val decl = tools.single().functionDeclarations.single()
        assertEquals("current_weather", decl.name)
        assertEquals("Get current weather", decl.description)
        assertEquals(weatherSchema, decl.parameters)
    }

    @Test
    fun `when message carries tool calls - then model content with functionCall parts`() {
        // given
        val args = buildJsonObject { put("city", "Paris") }
        val msg = Message(Role.ASSISTANT, "", toolCalls = listOf(ToolCall("current_weather", args)))

        // when
        val content = msg.toContentOrNull()

        // then
        assertEquals("model", content?.role)
        val fc = content?.parts?.single()?.functionCall
        assertEquals("current_weather", fc?.name)
        assertEquals(args, fc?.args)
    }

    @Test
    fun `when message carries a tool result - then user content with functionResponse object`() {
        // given
        val msg = Message(Role.USER, "Paris: 18C", toolResultFor = "current_weather")

        // when
        val content = msg.toContentOrNull()

        // then
        assertEquals("user", content?.role)
        val fr = content?.parts?.single()?.functionResponse
        assertEquals("current_weather", fr?.name)
        assertEquals(JsonPrimitive("Paris: 18C"), fr?.response?.get("result"))
    }

    @Test
    fun `when plain user message - then single text part unchanged`() {
        // given
        val msg = Message(Role.USER, "hi")

        // when
        val content = msg.toContentOrNull()

        // then
        assertEquals("user", content?.role)
        assertEquals("hi", content?.parts?.single()?.text)
        assertNull(content?.parts?.single()?.functionCall)
    }

    @Test
    fun `when response has a functionCall part - then extractToolCalls returns it`() {
        // given
        val args = buildJsonObject { put("city", "Tokyo") }
        val response = GeminiResponse(
            candidates = listOf(Candidate(Content(parts = listOf(Part(functionCall = FunctionCall("current_weather", args)))))),
        )

        // when
        val calls = response.extractToolCalls()

        // then
        assertEquals(listOf(ToolCall("current_weather", args)), calls)
    }

    @Test
    fun `when response has only a functionCall - then extractText is null`() {
        // given
        val response = GeminiResponse(
            candidates = listOf(Candidate(Content(parts = listOf(Part(functionCall = FunctionCall("current_weather")))))),
        )

        // when - then
        assertNull(response.extractText())
    }

    @Test
    fun `when response has text parts - then extractText joins them and no tool calls`() {
        // given
        val response = GeminiResponse(
            candidates = listOf(Candidate(Content(parts = listOf(Part(text = "It is "), Part(text = "sunny."))))),
        )

        // when - then
        assertEquals("It is sunny.", response.extractText())
        assertEquals(emptyList<ToolCall>(), response.extractToolCalls())
    }
}

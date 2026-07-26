package ru.den.writes.code.agenticHub.features.llm.ollama

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.llm.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OllamaApiTest {

    @Test
    fun `when no SYSTEM input and no endSequence - then no system message is emitted`() {
        // given
        val messages = listOf(
            Message(Role.USER, "hi"),
            Message(Role.ASSISTANT, "hello"),
        )

        // when
        val actual = buildOllamaWireMessages(messages, endSequence = null)

        // then
        assertEquals(2, actual.size)
        assertEquals("user", actual[0].role)
        assertEquals("assistant", actual[1].role)
    }

    @Test
    fun `when only endSequence is set - then one system message sits at the head`() {
        // given
        val messages = listOf(Message(Role.USER, "hi"))

        // when
        val actual = buildOllamaWireMessages(messages, endSequence = "<<DONE>>")

        // then
        assertEquals(2, actual.size)
        assertEquals("system", actual[0].role)
        assertEquals("Always end your response with the literal text: \"<<DONE>>\"", actual[0].content)
        assertEquals("user", actual[1].role)
    }

    @Test
    fun `when several SYSTEM messages are passed - then they collapse into one system message`() {
        // given
        val messages = listOf(
            Message(Role.SYSTEM, "[Profile]\nP"),
            Message(Role.SYSTEM, "[Rules]\n- R1"),
            Message(Role.USER, "hi"),
        )

        // when
        val actual = buildOllamaWireMessages(messages, endSequence = null)

        // then
        assertEquals(2, actual.size)
        assertEquals("system", actual[0].role)
        assertEquals("[Profile]\nP\n\n[Rules]\n- R1", actual[0].content)
        assertEquals("user", actual[1].role)
    }

    @Test
    fun `when SYSTEM messages and endSequence are passed - then they merge into one system message`() {
        // given
        val messages = listOf(
            Message(Role.SYSTEM, "[Profile]\nP"),
            Message(Role.USER, "hi"),
        )

        // when
        val actual = buildOllamaWireMessages(messages, endSequence = "<<DONE>>")

        // then
        assertEquals(2, actual.size)
        assertEquals("system", actual[0].role)
        assertEquals(
            "[Profile]\nP\n\nAlways end your response with the literal text: \"<<DONE>>\"",
            actual[0].content,
        )
    }

    @Test
    fun `when a SYSTEM message sits mid-list - then it is collected to the front`() {
        // given
        val messages = listOf(
            Message(Role.USER, "hello"),
            Message(Role.SYSTEM, "[Rules]\n- R1"),
            Message(Role.ASSISTANT, "world"),
        )

        // when
        val actual = buildOllamaWireMessages(messages, endSequence = null)

        // then
        assertEquals(3, actual.size)
        assertEquals("system", actual[0].role)
        assertEquals("[Rules]\n- R1", actual[0].content)
        assertEquals("user", actual[1].role)
        assertEquals("hello", actual[1].content)
        assertEquals("assistant", actual[2].role)
        assertEquals("world", actual[2].content)
    }

    @Test
    fun `when a turn carries tool calls - then it maps to an assistant turn with tool_calls`() {
        // given
        val call = ToolCall(name = "read_project_file", arguments = buildJsonObject { put("path", "README.md") })
        val messages = listOf(Message(Role.ASSISTANT, "", toolCalls = listOf(call)))

        // when
        val actual = buildOllamaWireMessages(messages, endSequence = null)

        // then
        assertEquals(1, actual.size)
        assertEquals("assistant", actual[0].role)
        val wireCall = actual[0].toolCalls?.single()?.function
        assertEquals("read_project_file", wireCall?.name)
        assertEquals(call.arguments, wireCall?.arguments)
    }

    @Test
    fun `when a turn carries a tool result - then it maps to a tool turn naming the tool`() {
        // given — the responder replays a result as Role.USER; the tool field decides the wire role
        val messages = listOf(Message(Role.USER, "file body", toolResultFor = "read_project_file"))

        // when
        val actual = buildOllamaWireMessages(messages, endSequence = null)

        // then
        assertEquals(1, actual.size)
        assertEquals("tool", actual[0].role)
        assertEquals("file body", actual[0].content)
        assertEquals("read_project_file", actual[0].toolName)
    }

    @Test
    fun `when a tool exchange is passed - then call result and answer keep their order`() {
        // given
        val call = ToolCall(name = "list_files", arguments = buildJsonObject { put("dir", "docs") })
        val messages = listOf(
            Message(Role.USER, "what is in docs?"),
            Message(Role.ASSISTANT, "", toolCalls = listOf(call)),
            Message(Role.USER, "a.md", toolResultFor = "list_files"),
            Message(Role.ASSISTANT, "one file"),
        )

        // when
        val actual = buildOllamaWireMessages(messages, endSequence = null)

        // then
        assertEquals(listOf("user", "assistant", "tool", "assistant"), actual.map { it.role })
        assertEquals("one file", actual[3].content)
    }

    @Test
    fun `when an ordinary turn is mapped - then it carries no tool fields`() {
        // given
        val messages = listOf(Message(Role.USER, "hi"))

        // when
        val actual = buildOllamaWireMessages(messages, endSequence = null)

        // then
        assertNull(actual[0].toolCalls)
        assertNull(actual[0].toolName)
    }
}

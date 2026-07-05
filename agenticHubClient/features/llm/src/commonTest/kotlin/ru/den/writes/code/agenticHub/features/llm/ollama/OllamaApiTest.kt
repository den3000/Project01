package ru.den.writes.code.agenticHub.features.llm.ollama

import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import kotlin.test.Test
import kotlin.test.assertEquals

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
}

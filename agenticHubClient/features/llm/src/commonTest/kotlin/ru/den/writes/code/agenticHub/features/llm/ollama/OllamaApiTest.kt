package ru.den.writes.code.agenticHub.features.llm.ollama

import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import kotlin.test.Test
import kotlin.test.assertEquals

class OllamaApiTest {

    // --- buildOllamaWireMessages ------------------------------------

    @Test
    fun `emits no system message when there is no SYSTEM input and no endSequence`() {
        val wire = buildOllamaWireMessages(
            listOf(
                Message(Role.USER, "hi"),
                Message(Role.ASSISTANT, "hello"),
            ),
            endSequence = null,
        )
        assertEquals(2, wire.size)
        assertEquals("user", wire[0].role)
        assertEquals("assistant", wire[1].role)
    }

    @Test
    fun `with only endSequence emits one system message at the head`() {
        val wire = buildOllamaWireMessages(
            listOf(Message(Role.USER, "hi")),
            endSequence = "<<DONE>>",
        )
        assertEquals(2, wire.size)
        assertEquals("system", wire[0].role)
        assertEquals(
            "Always end your response with the literal text: \"<<DONE>>\"",
            wire[0].content,
        )
        assertEquals("user", wire[1].role)
    }

    @Test
    fun `collapses multiple SYSTEM messages into one combined role system message`() {
        val wire = buildOllamaWireMessages(
            listOf(
                Message(Role.SYSTEM, "[Profile]\nP"),
                Message(Role.SYSTEM, "[Rules]\n- R1"),
                Message(Role.USER, "hi"),
            ),
            endSequence = null,
        )
        assertEquals(2, wire.size)
        assertEquals("system", wire[0].role)
        assertEquals("[Profile]\nP\n\n[Rules]\n- R1", wire[0].content)
        assertEquals("user", wire[1].role)
    }

    @Test
    fun `SYSTEM messages and endSequence merge into a single system message`() {
        val wire = buildOllamaWireMessages(
            listOf(
                Message(Role.SYSTEM, "[Profile]\nP"),
                Message(Role.USER, "hi"),
            ),
            endSequence = "<<DONE>>",
        )
        assertEquals(2, wire.size)
        assertEquals("system", wire[0].role)
        assertEquals(
            "[Profile]\nP\n\nAlways end your response with the literal text: \"<<DONE>>\"",
            wire[0].content,
        )
    }

    @Test
    fun `SYSTEM messages mid-list are still collected to the front`() {
        val wire = buildOllamaWireMessages(
            listOf(
                Message(Role.USER, "hello"),
                Message(Role.SYSTEM, "[Rules]\n- R1"),
                Message(Role.ASSISTANT, "world"),
            ),
            endSequence = null,
        )
        assertEquals(3, wire.size)
        assertEquals("system", wire[0].role)
        assertEquals("[Rules]\n- R1", wire[0].content)
        assertEquals("user", wire[1].role)
        assertEquals("hello", wire[1].content)
        assertEquals("assistant", wire[2].role)
        assertEquals("world", wire[2].content)
    }
}

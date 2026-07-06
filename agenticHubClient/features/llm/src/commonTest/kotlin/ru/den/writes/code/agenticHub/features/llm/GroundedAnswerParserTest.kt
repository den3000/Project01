package ru.den.writes.code.agenticHub.features.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Backtick names без `()`/`,` — иначе iOS commonTest не компилится.
class GroundedAnswerParserTest {

    @Test
    fun `when reply is a clean json object - then answer citations and known are parsed`() {
        // given
        val reply = """
            {"answer":"3 approvals","known":true,"citations":[
              {"source":"handbook/zephyr.md","section":"Code Review Policy","chunk_id":1,"quote":"requires exactly 3 approvals"}]}
        """.trimIndent()

        // when
        val actual = parseGroundedAnswer(reply)

        // then
        assertEquals("3 approvals", actual.answer)
        assertTrue(actual.isKnown)
        val c = actual.citations.single()
        assertEquals("handbook/zephyr.md", c.source)
        assertEquals("Code Review Policy", c.section)
        assertEquals(1, c.chunkId)
        assertEquals("requires exactly 3 approvals", c.quote)
    }

    @Test
    fun `when the json is wrapped in a fenced block and prose - then it is still parsed`() {
        // given
        val reply = """
            Sure, here you go:
            ```json
            {"answer":"5 days","known":true,"citations":[{"source":"s","section":null,"chunk_id":0,"quote":"lasts 5 days"}]}
            ```
        """.trimIndent()

        // when
        val actual = parseGroundedAnswer(reply)

        // then
        assertEquals("5 days", actual.answer)
        assertEquals(null, actual.citations.single().section)
        assertEquals("lasts 5 days", actual.citations.single().quote)
    }

    @Test
    fun `when known is false - then the answer is marked not known and carries no citations`() {
        // given
        val reply = """{"answer":"I don't know — please clarify","known":false,"citations":[]}"""

        // when
        val actual = parseGroundedAnswer(reply)

        // then
        assertFalse(actual.isKnown)
        assertTrue(actual.citations.isEmpty())
    }

    @Test
    fun `when a citation lacks a quote - then it is dropped`() {
        // given
        val reply = """
            {"answer":"x","known":true,"citations":[
              {"source":"s","chunk_id":0,"quote":""},
              {"source":"s2","chunk_id":1,"quote":"real quote"}]}
        """.trimIndent()

        // when
        val actual = parseGroundedAnswer(reply)

        // then
        assertEquals(listOf("real quote"), actual.citations.map { it.quote })
    }

    @Test
    fun `when the reply has no json object - then it is not known and has no citations`() {
        // given - when
        val actual = parseGroundedAnswer("I'm not sure how to answer that.")

        // then
        assertFalse(actual.isKnown)
        assertTrue(actual.citations.isEmpty())
    }

    @Test
    fun `when the json is malformed - then it falls back to not known`() {
        // given - when
        val actual = parseGroundedAnswer("""{"answer": "oops", "known": tru}""")

        // then
        assertFalse(actual.isKnown)
        assertTrue(actual.citations.isEmpty())
    }

    @Test
    fun `when known is true but the answer is blank - then it is not known`() {
        // given - when
        val actual = parseGroundedAnswer("""{"answer":"","known":true,"citations":[]}""")

        // then
        assertFalse(actual.isKnown)
    }
}

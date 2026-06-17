package ru.den.writes.code.project01.cliJvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FactsExtractorTest {

    //region buildExtractionPrompt

    @Test
    fun `when buildExtractionPrompt called with prior facts and new message - then both included`() {
        // given
        val priorFacts = """{"name":"Denis"}"""
        val newMessage = "my budget is 50"

        // when
        val actual = FactsExtractor.buildExtractionPrompt(priorFacts, newMessage)

        // then
        assertTrue(actual.contains(priorFacts), "prior facts missing from prompt")
        assertTrue(actual.contains(newMessage), "new message missing from prompt")
    }

    @Test
    fun `when buildExtractionPrompt called with null prior - then empty object shown`() {
        // given
        val priorFacts: String? = null
        val newMessage = "hello"

        // when
        val actual = FactsExtractor.buildExtractionPrompt(priorFacts, newMessage)

        // then
        assertTrue(actual.contains("{}"), "empty-object placeholder missing")
        assertTrue(actual.contains(newMessage), "new message missing from prompt")
    }
    //endregion

    //region mergeOrKeep — happy path

    @Test
    fun `when mergeOrKeep called with valid object reply - then compact json returned`() {
        // given
        val reply = """{ "k": "v" }"""

        // when
        val actual = FactsExtractor.mergeOrKeep(priorJson = null, replyText = reply)

        // then
        val expected = """{"k":"v"}"""
        assertEquals(expected, actual)
    }

    @Test
    fun `when mergeOrKeep called with json code fence reply - then content extracted`() {
        // given
        val reply = "```json\n{\"k\":\"v\"}\n```"

        // when
        val actual = FactsExtractor.mergeOrKeep(priorJson = null, replyText = reply)

        // then
        val expected = """{"k":"v"}"""
        assertEquals(expected, actual)
    }
    //endregion

    //region mergeOrKeep — fallback to prior

    @Test
    fun `when mergeOrKeep called with non-json reply - then prior facts kept`() {
        // given
        val prior = """{"k":"prior"}"""
        val reply = "Sure, here are the facts: ..."

        // when
        val actual = FactsExtractor.mergeOrKeep(priorJson = prior, replyText = reply)

        // then
        val expected = """{"k":"prior"}"""
        assertEquals(expected, actual)
    }

    @Test
    fun `when mergeOrKeep called with null or blank reply - then prior facts kept`() {
        // given
        // Same invariant ("no usable reply → keep prior") covered for both
        // null and blank inputs.
        val prior = """{"k":"p"}"""

        // when
        val nullReplyActual = FactsExtractor.mergeOrKeep(priorJson = prior, replyText = null)
        val blankReplyActual = FactsExtractor.mergeOrKeep(priorJson = prior, replyText = "   ")

        // then
        val expected = """{"k":"p"}"""
        assertEquals(expected, nullReplyActual)
        assertEquals(expected, blankReplyActual)
    }
    //endregion

    //region mergeOrKeep — unusable reply with no prior

    @Test
    fun `when mergeOrKeep called with no prior and unusable reply - then null returned`() {
        // given
        val reply = "garbage"

        // when
        val actual = FactsExtractor.mergeOrKeep(priorJson = null, replyText = reply)

        // then
        assertNull(actual)
    }

    @Test
    fun `when mergeOrKeep called with json array reply - then null returned (must be object)`() {
        // given
        val reply = "[1, 2, 3]"

        // when
        val actual = FactsExtractor.mergeOrKeep(priorJson = null, replyText = reply)

        // then
        assertNull(actual)
    }
    //endregion
}

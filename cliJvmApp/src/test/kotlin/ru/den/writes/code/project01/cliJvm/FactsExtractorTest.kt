package ru.den.writes.code.project01.cliJvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FactsExtractorTest {

    @Test
    fun `buildExtractionPrompt includes prior facts and the new message`() {
        val p = FactsExtractor.buildExtractionPrompt("""{"name":"Denis"}""", "my budget is 50")
        assertTrue(p.contains("""{"name":"Denis"}"""))
        assertTrue(p.contains("my budget is 50"))
    }

    @Test
    fun `buildExtractionPrompt shows empty facts as an empty object when prior is null`() {
        val p = FactsExtractor.buildExtractionPrompt(null, "hello")
        assertTrue(p.contains("{}"))
        assertTrue(p.contains("hello"))
    }

    @Test
    fun `mergeOrKeep returns compact json on a valid object reply`() {
        assertEquals("""{"k":"v"}""", FactsExtractor.mergeOrKeep(null, """{ "k": "v" }"""))
    }

    @Test
    fun `mergeOrKeep tolerates a json code fence`() {
        assertEquals(
            """{"k":"v"}""",
            FactsExtractor.mergeOrKeep(null, "```json\n{\"k\":\"v\"}\n```"),
        )
    }

    @Test
    fun `mergeOrKeep keeps prior facts on a non-json reply`() {
        assertEquals(
            """{"k":"prior"}""",
            FactsExtractor.mergeOrKeep("""{"k":"prior"}""", "Sure, here are the facts: ..."),
        )
    }

    @Test
    fun `mergeOrKeep keeps prior facts on null or blank reply`() {
        assertEquals("""{"k":"p"}""", FactsExtractor.mergeOrKeep("""{"k":"p"}""", null))
        assertEquals("""{"k":"p"}""", FactsExtractor.mergeOrKeep("""{"k":"p"}""", "   "))
    }

    @Test
    fun `mergeOrKeep returns null when there is no prior and the reply is unusable`() {
        assertNull(FactsExtractor.mergeOrKeep(null, "garbage"))
    }

    @Test
    fun `mergeOrKeep rejects a json array (must be an object)`() {
        assertNull(FactsExtractor.mergeOrKeep(null, "[1, 2, 3]"))
    }
}

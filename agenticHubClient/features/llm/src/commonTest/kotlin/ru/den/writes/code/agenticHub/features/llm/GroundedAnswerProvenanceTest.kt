package ru.den.writes.code.agenticHub.features.llm

import ru.den.writes.code.agenticHub.features.rag.chunking.Chunk
import ru.den.writes.code.agenticHub.features.rag.chunking.ChunkMetadata
import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Backtick names без `()`/`,` — иначе iOS commonTest не компилится.
class GroundedAnswerProvenanceTest {

    @Test
    fun `when a quote matches a chunk - then provenance is rewritten from that chunk's real metadata`() {
        // given — the model claimed a bogus source and chunk_id; the quote is verbatim from chunk #7
        val answer = GroundedAnswer(
            answer = "30 days",
            citations = listOf(Citation(source = "made up", section = "Wrong", chunkId = 0, quote = "retained for 30 days")),
            isKnown = true,
        )
        val chunks = listOf(chunk("Application logs are retained for 30 days.", section = "Data Retention", chunkId = 7))

        // when
        val actual = answer.groundedIn(chunks)

        // then — source/section/chunkId now come from the real chunk, quote untouched
        val c = actual.citations.single()
        assertEquals("src", c.source)
        assertEquals("Data Retention", c.section)
        assertEquals(7, c.chunkId)
        assertEquals("retained for 30 days", c.quote)
    }

    @Test
    fun `when a quote is in no chunk - then that citation is dropped as hallucinated`() {
        // given
        val answer = GroundedAnswer(
            answer = "x",
            citations = listOf(
                Citation("s", "A", 0, "totally invented quote"),
                Citation("s", "B", 1, "retained for 30 days"),
            ),
            isKnown = true,
        )
        val chunks = listOf(chunk("Application logs are retained for 30 days.", section = "Data Retention", chunkId = 7))

        // when
        val actual = answer.groundedIn(chunks)

        // then
        assertEquals(listOf("retained for 30 days"), actual.citations.map { it.quote })
    }

    @Test
    fun `when a quote differs only in whitespace - then it still matches`() {
        // given — model collapsed the newline/spacing in the quote
        val answer = GroundedAnswer(
            answer = "x",
            citations = listOf(Citation("s", null, 0, "a first response within 15 minutes")),
            isKnown = true,
        )
        val chunks = listOf(chunk("must receive a first response\n   within 15 minutes", section = "Incident Severity", chunkId = 4))

        // when
        val actual = answer.groundedIn(chunks)

        // then
        assertTrue(actual.citations.isNotEmpty())
        assertEquals("Incident Severity", actual.citations.single().section)
    }

    private fun chunk(text: String, section: String?, chunkId: Int): ScoredChunk =
        ScoredChunk(
            chunk = Chunk(text, ChunkMetadata(source = "src", title = "t", section = section, chunkId = chunkId)),
            score = 0.9,
        )
}

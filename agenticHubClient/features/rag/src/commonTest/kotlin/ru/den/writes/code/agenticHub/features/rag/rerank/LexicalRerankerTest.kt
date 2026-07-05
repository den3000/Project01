package ru.den.writes.code.agenticHub.features.rag.rerank

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.rag.chunking.Chunk
import ru.den.writes.code.agenticHub.features.rag.chunking.ChunkMetadata
import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LexicalRerankerTest {

    @Test
    fun `when a candidate shares more query terms - then it outranks a higher-cosine one that shares none`() = runTest {
        // given — the term-poor chunk has the HIGHER input cosine, so a pass-through would keep it first
        val reranker = LexicalReranker(threshold = 0.0, topKAfter = 10)
        val candidates = listOf(
            scored("tomatoes need soil and water", cosine = 0.9),
            scored("cosine similarity powers vector search", cosine = 0.1),
        )

        // when
        val actual = reranker.rerank("cosine similarity search", candidates)

        // then — reordered by the lexical signal, and the surviving score is that signal (3/3), not the cosine
        assertEquals(
            listOf("cosine similarity powers vector search", "tomatoes need soil and water"),
            actual.map { it.chunk.text },
        )
        assertEquals(1.0, actual.first().score)
    }

    @Test
    fun `when threshold set - then candidates scoring below it are dropped`() = runTest {
        // given
        val reranker = LexicalReranker(threshold = 0.5, topKAfter = 10)
        val candidates = listOf(
            scored("cosine similarity vector search"), // 3/3 = 1.0 → kept
            scored("a search engine index"),           // 1/3 ≈ 0.33 → dropped
            scored("tomatoes and soil"),               // 0/3 = 0.0 → dropped
        )

        // when
        val actual = reranker.rerank("cosine similarity search", candidates)

        // then
        assertEquals(listOf("cosine similarity vector search"), actual.map { it.chunk.text })
    }

    @Test
    fun `when survivors exceed topKAfter - then only the best topKAfter are kept`() = runTest {
        // given
        val reranker = LexicalReranker(threshold = 0.0, topKAfter = 1)
        val candidates = listOf(
            scored("search only"),                   // 1/3
            scored("cosine similarity search full"), // 3/3
        )

        // when
        val actual = reranker.rerank("cosine similarity search", candidates)

        // then
        assertEquals(listOf("cosine similarity search full"), actual.map { it.chunk.text })
    }

    @Test
    fun `when topKAfter is zero - then empty`() = runTest {
        // given
        val reranker = LexicalReranker(threshold = 0.0, topKAfter = 0)

        // when
        val actual = reranker.rerank("cosine", listOf(scored("cosine similarity")))

        // then
        assertTrue(actual.isEmpty())
    }

    @Test
    fun `when candidates empty - then empty`() = runTest {
        // given
        val reranker = LexicalReranker(threshold = 0.0, topKAfter = 5)

        // when
        val actual = reranker.rerank("cosine similarity search", emptyList())

        // then
        assertTrue(actual.isEmpty())
    }

    @Test
    fun `when every candidate is below threshold - then empty`() = runTest {
        // given
        val reranker = LexicalReranker(threshold = 0.9, topKAfter = 5)
        val candidates = listOf(
            scored("only search here"), // 1/3 ≈ 0.33
            scored("nothing relevant"), // 0/3
        )

        // when
        val actual = reranker.rerank("cosine similarity search", candidates)

        // then
        assertTrue(actual.isEmpty())
    }

    @Test
    fun `when scores tie - then input order is preserved`() = runTest {
        // given — both share the single query term, so both score 1.0
        val reranker = LexicalReranker(threshold = 0.0, topKAfter = 10)
        val candidates = listOf(scored("alpha one"), scored("alpha two"))

        // when
        val actual = reranker.rerank("alpha", candidates)

        // then
        assertEquals(listOf("alpha one", "alpha two"), actual.map { it.chunk.text })
    }

    @Test
    fun `when query has no terms - then candidates all score zero and keep input order`() = runTest {
        // given
        val reranker = LexicalReranker(threshold = 0.0, topKAfter = 10)
        val candidates = listOf(scored("cosine similarity"), scored("tomatoes"))

        // when
        val actual = reranker.rerank("   ", candidates)

        // then
        assertEquals(listOf("cosine similarity", "tomatoes"), actual.map { it.chunk.text })
        actual.forEach { assertEquals(0.0, it.score) }
    }

    private fun scored(text: String, cosine: Double = 0.0): ScoredChunk =
        ScoredChunk(
            chunk = Chunk(text, ChunkMetadata(source = "s", title = "t", section = null, chunkId = 0)),
            score = cosine,
        )
}

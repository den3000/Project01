package ru.den.writes.code.agenticHub.features.rag.indexing

import ru.den.writes.code.agenticHub.features.rag.chunking.Chunk
import ru.den.writes.code.agenticHub.features.rag.chunking.ChunkMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VectorIndexTest {

    @Test
    fun `when searching - then results are ordered by descending score`() {
        // given
        val farEmbedding = listOf(0f, 1f)
        val exactEmbedding = listOf(1f, 0f)
        val midEmbedding = listOf(1f, 1f)
        val index = VectorIndex(
            listOf(
                indexed("far", farEmbedding),
                indexed("mid", midEmbedding),
                indexed("exact", exactEmbedding),
            ),
        )

        // when
        val actual = index.search(query = exactEmbedding, topK = 3)

        // then
        assertEquals(listOf("exact", "mid", "far"), actual.map { it.chunk.text })
    }

    @Test
    fun `when topK smaller than index - then only topK returned`() {
        // given
        val exactEmbedding = listOf(1f, 0f)
        val midEmbedding = listOf(1f, 1f)
        val farEmbedding = listOf(0f, 1f)
        val index = VectorIndex(
            listOf(
                indexed("far", farEmbedding),
                indexed("mid", midEmbedding),
                indexed("exact", exactEmbedding),
            ),
        )

        // when
        val actual = index.search(query = exactEmbedding, topK = 2)

        // then
        assertEquals(listOf("exact", "mid"), actual.map { it.chunk.text })
    }

    @Test
    fun `when topK exceeds index size - then all chunks returned`() {
        // given
        val aEmbedding = listOf(1f, 0f)
        val bEmbedding = listOf(0f, 1f)
        val index = VectorIndex(
            listOf(
                indexed("a", aEmbedding),
                indexed("b", bEmbedding),
            ),
        )

        // when
        val actual = index.search(query = aEmbedding, topK = 10)

        // then
        assertEquals(2, actual.size)
    }

    @Test
    fun `when topK is zero - then empty`() {
        // given
        val aEmbedding = listOf(1f, 0f)
        val index = VectorIndex(listOf(indexed("a", aEmbedding)))

        // when
        val actual = index.search(query = aEmbedding, topK = 0)

        // then
        assertTrue(actual.isEmpty())
    }

    @Test
    fun `when index empty - then empty`() {
        // given
        val queryEmbedding = listOf(1f, 0f)
        val index = VectorIndex(emptyList())

        // when
        val actual = index.search(query = queryEmbedding, topK = 5)

        // then
        assertTrue(actual.isEmpty())
    }

    private fun indexed(text: String, embedding: List<Float>): IndexedChunk =
        IndexedChunk(
            chunk = Chunk(text, ChunkMetadata(source = "s", title = "t", section = null, chunkId = 0)),
            embedding = embedding,
        )
}

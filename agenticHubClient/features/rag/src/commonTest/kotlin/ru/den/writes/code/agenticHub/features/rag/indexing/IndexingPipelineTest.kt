package ru.den.writes.code.agenticHub.features.rag.indexing

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.rag.chunking.FixedSizeChunking
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.chunking.doc
import ru.den.writes.code.agenticHub.features.rag.embedding.EmbedderFake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndexingPipelineTest {

    @Test
    fun `when indexing documents - then every chunk becomes an indexed chunk with a vector`() = runTest {
        // given
        val pipeline = IndexingPipeline(FixedSizeChunking(chunkSize = 4, overlap = 0), EmbedderFake())

        // when
        val actual = pipeline.index(listOf(doc("abcdefghij")))

        // then
        assertEquals(3, actual.chunks.size)
        assertTrue(actual.chunks.all { it.embedding.isNotEmpty() })
    }

    @Test
    fun `when indexing multiple documents - then chunks from all of them are indexed`() = runTest {
        // given
        val pipeline = IndexingPipeline(StructuralChunking(), EmbedderFake())

        // when
        val actual = pipeline.index(listOf(doc("# A\na"), doc("# B\nb")))

        // then
        assertEquals(listOf("A", "B"), actual.chunks.map { it.chunk.metadata.section })
    }

    @Test
    fun `when documents produce no chunks - then index is empty`() = runTest {
        // given
        val pipeline = IndexingPipeline(FixedSizeChunking(chunkSize = 4), EmbedderFake())

        // when
        val actual = pipeline.index(listOf(doc("   ")))

        // then
        assertTrue(actual.chunks.isEmpty())
    }
}

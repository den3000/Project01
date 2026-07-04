package ru.den.writes.code.agenticHub.features.rag

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.rag.chunking.FixedSizeChunking
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.chunking.TokenChunking
import ru.den.writes.code.agenticHub.features.rag.embedding.EmbedderFake
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetrieverTest {

    @Test
    fun `when index built by any chunking strategy - then retrieval surfaces the relevant chunk`() = runTest {
        // given
        val embedder = EmbedderFake()
        val strategies = listOf(
            FixedSizeChunking(chunkSize = 60),
            TokenChunking(tokensPerChunk = 8),
            StructuralChunking(),
        )

        strategies.forEach { strategy ->
            val index = IndexingPipeline(strategy, embedder).index(listOf(knowledgeDoc()))
            val retriever = Retriever(embedder, index)

            // when
            val actual = retriever.retrieve("embeddings and cosine similarity", topK = 1)

            // then
            assertTrue(
                actual.single().chunk.text.contains("embeddings"),
                "strategy ${strategy::class.simpleName}: top chunk was \"${actual.single().chunk.text}\"",
            )
        }
    }

    @Test
    fun `when structural chunking used - then retrieved chunk carries its section`() = runTest {
        // given
        val embedder = EmbedderFake()
        val index = IndexingPipeline(StructuralChunking(), embedder).index(listOf(knowledgeDoc()))
        val retriever = Retriever(embedder, index)

        // when
        val actual = retriever.retrieve("how do embeddings and cosine similarity work", topK = 2)

        // then
        assertEquals("Vector search", actual.first().chunk.metadata.section)
    }

    @Test
    fun `when topK given - then at most topK results returned`() = runTest {
        // given
        val embedder = EmbedderFake()
        val index = IndexingPipeline(StructuralChunking(), embedder).index(listOf(knowledgeDoc()))
        val retriever = Retriever(embedder, index)

        // when
        val actual = retriever.retrieve("gardening tomatoes", topK = 1)

        // then
        assertEquals(1, actual.size)
        assertEquals("Gardening", actual.single().chunk.metadata.section)
    }

    private fun knowledgeDoc() = doc(
        source = "kb.md",
        title = "kb.md",
        text = "# Vector search\n" +
            "embeddings and cosine similarity power vector search over documents\n\n" +
            "# Gardening\n" +
            "tomatoes need sunlight water and rich soil to grow well",
    )
}

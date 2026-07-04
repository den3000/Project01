package ru.den.writes.code.agenticHub.features.rag

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.rag.chunking.SourceDocument
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.embedding.FakeEmbedder
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline
import kotlin.test.Test
import kotlin.test.assertEquals

class RetrieverTest {

    @Test
    fun `when querying - then the most relevant chunk ranks first`() = runTest {
        // given
        val embedder = FakeEmbedder()
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
        val embedder = FakeEmbedder()
        val index = IndexingPipeline(StructuralChunking(), embedder).index(listOf(knowledgeDoc()))
        val retriever = Retriever(embedder, index)

        // when
        val actual = retriever.retrieve("gardening tomatoes", topK = 1)

        // then
        assertEquals(1, actual.size)
        assertEquals("Gardening", actual.single().chunk.metadata.section)
    }

    private fun knowledgeDoc(): SourceDocument = SourceDocument(
        source = "kb.md",
        title = "kb.md",
        text = "# Vector search\n" +
            "embeddings and cosine similarity power vector search over documents\n\n" +
            "# Gardening\n" +
            "tomatoes need sunlight water and rich soil to grow well",
    )
}

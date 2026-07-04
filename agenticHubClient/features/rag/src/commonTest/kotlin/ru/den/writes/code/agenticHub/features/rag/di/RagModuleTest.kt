package ru.den.writes.code.agenticHub.features.rag.di

import kotlinx.coroutines.test.runTest
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.rag.Retriever
import ru.den.writes.code.agenticHub.features.rag.chunking.Chunk
import ru.den.writes.code.agenticHub.features.rag.chunking.ChunkMetadata
import ru.den.writes.code.agenticHub.features.rag.chunking.SourceDocument
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.embedding.Embedder
import ru.den.writes.code.agenticHub.features.rag.embedding.EmbedderFake
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexStore
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexedChunk
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline
import ru.den.writes.code.agenticHub.features.rag.indexing.VectorIndex
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemTestModule
import ru.den.writes.code.agenticHub.testutils.IgnoreIos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// @IgnoreIos: fileSystemTestModule shares a file with the eager, iOS-TODO
// fileSystemModule val, which Kotlin/Native initializes on first touch.
@IgnoreIos
class RagModuleTest {

    // Один граф на класс; только ragTestModule (включает sharedRagModule + IndexStore-factory
    // + EmbedderFake) + fileSystemTestModule. Всё на factory → каждый get() свежий, тесты независимы.
    private val koin = koinApplication {
        modules(ragTestModule, fileSystemTestModule)
    }.koin

    @Test
    fun `when graph drives index then retrieve - then relevant chunk ranks first`() = runTest {
        // given
        val pipeline = koin.get<IndexingPipeline> { parametersOf(StructuralChunking()) }
        val index = pipeline.index(listOf(knowledgeDoc()))

        // when
        val retriever = koin.get<Retriever> { parametersOf(index) }
        val actual = retriever.retrieve("embeddings and cosine similarity", topK = 2)

        // then
        assertEquals("Vector search", actual.first().chunk.metadata.section)
    }

    @Test
    fun `when graph resolves index store - then save and load round-trip`() {
        // given
        val store = koin.get<IndexStore>()
        val index = sampleIndex()

        // when
        store.save(index, "indexes/kb.json")
        val actual = store.load("indexes/kb.json")

        // then
        assertEquals(index, actual)
    }

    @Test
    fun `when graph resolves embedder - then it is the offline fake`() {
        // given - when
        val actual = koin.get<Embedder>()

        // then
        assertTrue(actual is EmbedderFake)
    }

    private fun knowledgeDoc(): SourceDocument = SourceDocument(
        source = "kb.md",
        title = "kb.md",
        text = "# Vector search\n" +
            "embeddings and cosine similarity power vector search over documents\n\n" +
            "# Gardening\n" +
            "tomatoes need sunlight water and rich soil to grow well",
    )

    private fun sampleIndex(): VectorIndex = VectorIndex(
        listOf(
            IndexedChunk(
                chunk = Chunk(
                    text = "hello",
                    metadata = ChunkMetadata(source = "kb.md", title = "kb.md", section = null, chunkId = 0),
                ),
                embedding = listOf(0.1f, 0.2f),
            ),
        ),
    )
}

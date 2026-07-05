package ru.den.writes.code.agenticHub.features.rag.di

import kotlinx.coroutines.test.runTest
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.rag.Retriever
import ru.den.writes.code.agenticHub.features.rag.SAMPLE_INDEX_PATH
import ru.den.writes.code.agenticHub.features.rag.VECTOR_SEARCH_SECTION
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.embedding.Embedder
import ru.den.writes.code.agenticHub.features.rag.embedding.EmbedderFake
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexStore
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline
import ru.den.writes.code.agenticHub.features.rag.knowledgeDoc
import ru.den.writes.code.agenticHub.features.rag.rerank.LexicalReranker
import ru.den.writes.code.agenticHub.features.rag.rerank.Reranker
import ru.den.writes.code.agenticHub.features.rag.sampleIndex
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemTestModule
import ru.den.writes.code.agenticHub.testutils.IgnoreIos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
        assertEquals(VECTOR_SEARCH_SECTION, actual.first().chunk.metadata.section)
    }

    @Test
    fun `when graph resolves index store - then save and load round-trip`() {
        // given
        val store = koin.get<IndexStore>()
        val index = sampleIndex()

        // when
        store.save(index, SAMPLE_INDEX_PATH)
        val actual = store.load(SAMPLE_INDEX_PATH)

        // then
        assertEquals(index, actual)
    }

    @Test
    fun `when graph resolves embedder - then it is the offline fake`() {
        // given - when
        val actual = koin.get<Embedder>()

        // then
        assertIs<EmbedderFake>(actual)
    }

    @Test
    fun `when graph resolves reranker - then it is the offline lexical one`() {
        // given - when
        val actual = koin.get<Reranker>()

        // then
        assertIs<LexicalReranker>(actual)
    }
}

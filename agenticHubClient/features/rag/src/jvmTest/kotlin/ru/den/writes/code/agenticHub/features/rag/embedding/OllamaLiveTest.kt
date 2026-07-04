package ru.den.writes.code.agenticHub.features.rag.embedding

import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.rag.Retriever
import ru.den.writes.code.agenticHub.features.rag.VECTOR_SEARCH_SECTION
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.di.ragModule
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline
import ru.den.writes.code.agenticHub.features.rag.knowledgeDoc
import ru.den.writes.code.agenticHub.features.rag.liveOllamaTest
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Opt-in live test (see LIVE_TESTS.md): the central gate excludes `*LiveTest` unless
// `-PliveTests` (or `liveTests=true` in ~/.gradle/gradle.properties for Android Studio's
// gutter-run). Reachability skip lives in liveOllamaTest. Package `...embedding` to reach
// the internal `cosineSimilarity`. The graph resolves the real production binding
// (ragModule's OllamaEmbedder over networkModule's HttpClient) against a live model.
class OllamaLiveTest {

    // One koin per test method (JUnit4 makes a fresh test instance per @Test → a fresh
    // graph); everything else is resolved from it inside each test, from scratch.
    private val koin = koinApplication { modules(ragModule, networkModule) }.koin

    @Test
    fun `when embedding one text - then a non-empty vector comes back`() = liveOllamaTest(koin) {
        // given
        val embedder = koin.get<Embedder>()

        // when
        val actual = embedder.embed(listOf("hello world"))

        // then
        assertEquals(1, actual.size)
        assertTrue(actual.single().isNotEmpty())
    }

    @Test
    fun `when embedding a batch - then vectors align and share one dimension`() = liveOllamaTest(koin) {
        // given
        val embedder = koin.get<Embedder>()

        // when
        val actual = embedder.embed(listOf("first text", "second text"))

        // then
        assertEquals(2, actual.size)
        assertEquals(actual[0].size, actual[1].size)
    }

    @Test
    fun `when texts are semantically related - then their cosine exceeds an unrelated pair`() = liveOllamaTest(koin) {
        // given
        val embedder = koin.get<Embedder>()
        val vectors = embedder.embed(
            listOf(
                "vector search over embeddings",
                "cosine similarity ranks embedding vectors",
                "tomatoes need sunlight and rich soil to grow",
            ),
        )

        // when
        val related = cosineSimilarity(vectors[0], vectors[1])
        val unrelated = cosineSimilarity(vectors[0], vectors[2])

        // then
        assertTrue(related > unrelated, "related=$related should exceed unrelated=$unrelated")
    }

    @Test
    fun `when indexed and queried through the real model - then the relevant section ranks first`() = liveOllamaTest(koin) {
        // given
        val embedder = koin.get<Embedder>()
        val index = IndexingPipeline(StructuralChunking(), embedder).index(listOf(knowledgeDoc()))
        val retriever = Retriever(embedder, index)

        // when
        val actual = retriever.retrieve("how do embeddings and cosine similarity work", topK = 1)

        // then
        assertEquals(VECTOR_SEARCH_SECTION, actual.single().chunk.metadata.section)
    }
}

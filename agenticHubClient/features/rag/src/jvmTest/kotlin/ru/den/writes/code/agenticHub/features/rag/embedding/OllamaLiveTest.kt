package ru.den.writes.code.agenticHub.features.rag.embedding

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.rag.Retriever
import ru.den.writes.code.agenticHub.features.rag.VECTOR_SEARCH_SECTION
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline
import ru.den.writes.code.agenticHub.features.rag.knowledgeDoc
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Opt-in: excluded from the normal jvmTest run (see build.gradle exclude on
// *OllamaLiveTest), run with `-PollamaLive` against a local Ollama. Even then, each
// test first probes reachability and skips (Assume) if Ollama isn't up. Package
// `...embedding` so it can use the internal `cosineSimilarity`.
class OllamaLiveTest {

    @Test
    fun `when embedding one text - then a non-empty vector comes back`() = runOllama { embedder ->
        // when
        val actual = embedder.embed(listOf("hello world"))

        // then
        assertEquals(1, actual.size)
        assertTrue(actual.single().isNotEmpty())
    }

    @Test
    fun `when embedding a batch - then vectors align and share one dimension`() = runOllama { embedder ->
        // when
        val actual = embedder.embed(listOf("first text", "second text"))

        // then
        assertEquals(2, actual.size)
        assertEquals(actual[0].size, actual[1].size)
    }

    @Test
    fun `when texts are semantically related - then their cosine exceeds an unrelated pair`() = runOllama { embedder ->
        // given
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
    fun `when indexed and queried through the real model - then the relevant section ranks first`() = runOllama { embedder ->
        // given
        val index = IndexingPipeline(StructuralChunking(), embedder).index(listOf(knowledgeDoc()))
        val retriever = Retriever(embedder, index)

        // when
        val actual = retriever.retrieve("how do embeddings and cosine similarity work", topK = 1)

        // then
        assertEquals(VECTOR_SEARCH_SECTION, actual.single().chunk.metadata.section)
    }

    private fun runOllama(block: suspend (OllamaEmbedder) -> Unit): TestResult = runTest {
        val app = koinApplication { modules(networkModule) }
        val client = app.koin.get<HttpClient>()
        try {
            assumeOllamaUp(client)
            block(OllamaEmbedder(client))
        } finally {
            app.close()
        }
    }

    private suspend fun assumeOllamaUp(client: HttpClient) {
        val reachable = try {
            client.get("$OLLAMA_BASE/api/tags").status.isSuccess()
        } catch (_: Exception) {
            false
        }
        assumeTrue("Ollama not reachable at $OLLAMA_BASE — skipping live test", reachable)
    }

    private companion object {
        const val OLLAMA_BASE = "http://localhost:11434"
    }
}

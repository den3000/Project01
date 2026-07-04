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

// Opt-in: excluded from the normal jvmTest run (build.gradle excludes *OllamaLiveTest),
// enabled with `-PollamaLive` (or `ollamaLive=true` in ~/.gradle/gradle.properties so
// Android Studio's gutter-run picks it up). Each test still probes reachability and
// Assume-skips if Ollama isn't up. Package `...embedding` to reach the internal
// `cosineSimilarity`.
class OllamaLiveTest {

    @Test
    fun `when embedding one text - then a non-empty vector comes back`() = liveTest {
        // when
        val actual = embedder.embed(listOf("hello world"))

        // then
        assertEquals(1, actual.size)
        assertTrue(actual.single().isNotEmpty())
    }

    @Test
    fun `when embedding a batch - then vectors align and share one dimension`() = liveTest {
        // when
        val actual = embedder.embed(listOf("first text", "second text"))

        // then
        assertEquals(2, actual.size)
        assertEquals(actual[0].size, actual[1].size)
    }

    @Test
    fun `when texts are semantically related - then their cosine exceeds an unrelated pair`() = liveTest {
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
    fun `when indexed and queried through the real model - then the relevant section ranks first`() = liveTest {
        // given
        val index = IndexingPipeline(StructuralChunking(), embedder).index(listOf(knowledgeDoc()))
        val retriever = Retriever(embedder, index)

        // when
        val actual = retriever.retrieve("how do embeddings and cosine similarity work", topK = 1)

        // then
        assertEquals(VECTOR_SEARCH_SECTION, actual.single().chunk.metadata.section)
    }

    private fun liveTest(block: suspend () -> Unit): TestResult = runTest {
        assumeOllamaUp()
        block()
    }

    private suspend fun assumeOllamaUp() {
        val reachable = try {
            client.get("$OLLAMA_BASE/api/tags").status.isSuccess()
        } catch (_: Exception) {
            false
        }
        assumeTrue("Ollama not reachable at $OLLAMA_BASE — skipping live test", reachable)
    }

    // One koin/client/embedder for the whole class: JVM-static → a single instance
    // across all test methods (a plain `val` would be rebuilt per method, since JUnit4
    // makes a fresh test instance per @Test). Stateless client → safe to share.
    private companion object {
        private val koin = koinApplication { modules(networkModule) }.koin
        val client: HttpClient = koin.get()
        val embedder = OllamaEmbedder(client)
        const val OLLAMA_BASE = "http://localhost:11434"
    }
}

package ru.den.writes.code.agenticHub.features.llm

import kotlinx.coroutines.test.runTest
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.llm.di.llmTestModule
import ru.den.writes.code.agenticHub.features.rag.chunking.Chunk
import ru.den.writes.code.agenticHub.features.rag.chunking.ChunkMetadata
import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Backtick names без `()`/`,` — иначе iOS commonTest не компилится.
class ModelRerankerTest {

    private val koin = koinApplication { modules(llmTestModule) }.koin

    @Test
    fun `when the model scores candidates - then they are reordered by the model score`() = runTest {
        // given — A has the higher input cosine, but the model scores B higher
        val script = koin.get<FakeLlmScript>()
        script.queueText("0.2") // for A
        script.queueText("0.9") // for B
        val reranker = reranker(script, threshold = 0.0, topKAfter = 10)
        val candidates = listOf(scored("a", cosine = 0.9), scored("b", cosine = 0.1))

        // when
        val actual = reranker.rerank("q", candidates)

        // then — reordered by the model score, and that score replaces the cosine
        assertEquals(listOf("b", "a"), actual.map { it.chunk.text })
        assertEquals(0.9, actual.first().score)
    }

    @Test
    fun `when a score is below threshold - then that candidate is dropped`() = runTest {
        // given
        val script = koin.get<FakeLlmScript>()
        script.queueText("0.9") // for A
        script.queueText("0.2") // for B
        val reranker = reranker(script, threshold = 0.5, topKAfter = 10)
        val candidates = listOf(scored("a"), scored("b"))

        // when
        val actual = reranker.rerank("q", candidates)

        // then
        assertEquals(listOf("a"), actual.map { it.chunk.text })
    }

    @Test
    fun `when survivors exceed topKAfter - then only the best are kept`() = runTest {
        // given
        val script = koin.get<FakeLlmScript>()
        script.queueText("0.7") // for A
        script.queueText("0.95") // for B
        val reranker = reranker(script, threshold = 0.0, topKAfter = 1)
        val candidates = listOf(scored("a"), scored("b"))

        // when
        val actual = reranker.rerank("q", candidates)

        // then
        assertEquals(listOf("b"), actual.map { it.chunk.text })
    }

    @Test
    fun `when topKAfter is zero - then empty and the model is never called`() = runTest {
        // given
        val script = koin.get<FakeLlmScript>()
        val reranker = reranker(script, threshold = 0.0, topKAfter = 0)

        // when
        val actual = reranker.rerank("q", listOf(scored("a")))

        // then
        assertTrue(actual.isEmpty())
        assertTrue(script.calls.isEmpty())
    }

    @Test
    fun `when candidates empty - then empty`() = runTest {
        // given
        val script = koin.get<FakeLlmScript>()
        val reranker = reranker(script, threshold = 0.0, topKAfter = 5)

        // when
        val actual = reranker.rerank("q", emptyList())

        // then
        assertTrue(actual.isEmpty())
    }

    @Test
    fun `when a model reply has no number - then that candidate scores zero`() = runTest {
        // given
        val script = koin.get<FakeLlmScript>()
        script.queueText("banana")
        val reranker = reranker(script, threshold = 0.0, topKAfter = 5)

        // when
        val actual = reranker.rerank("q", listOf(scored("a")))

        // then
        assertEquals(0.0, actual.single().score)
    }

    @Test
    fun `when scoring - then the query and the passage are sent to the model`() = runTest {
        // given
        val script = koin.get<FakeLlmScript>()
        script.queueText("0.9")
        val reranker = reranker(script, threshold = 0.0, topKAfter = 5)

        // when
        reranker.rerank("how many approvals", listOf(scored("the answer is 3 approvals")))

        // then
        val sent = script.calls.single().messages.first { it.role == Role.USER }.text
        assertTrue("how many approvals" in sent)
        assertTrue("the answer is 3 approvals" in sent)
    }

    private fun reranker(script: FakeLlmScript, threshold: Double, topKAfter: Int): ModelReranker =
        ModelReranker(
            llmApi = koin.get { parametersOf(script) },
            threshold = threshold,
            topKAfter = topKAfter,
        )

    private fun scored(text: String, cosine: Double = 0.0): ScoredChunk =
        ScoredChunk(
            chunk = Chunk(text, ChunkMetadata(source = "s", title = "t", section = null, chunkId = 0)),
            score = cosine,
        )
}

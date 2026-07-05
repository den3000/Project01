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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Backtick names без `()`/`,` — иначе iOS commonTest не компилится.
class GroundedAnswererTest {

    private val koin = koinApplication { modules(llmTestModule) }.koin

    @Test
    fun `when the top score is below threshold - then it answers not known without calling the model`() = runTest {
        // given
        val script = koin.get<FakeLlmScript>()
        val answerer = GroundedAnswerer(koin.get { parametersOf(script) }, relevanceThreshold = 0.6)

        // when
        val actual = answerer.answer("q", listOf(scored("some off-topic text", score = 0.3)))

        // then — the gate fired in code, no model call
        assertFalse(actual.isKnown)
        assertTrue(actual.citations.isEmpty())
        assertTrue(script.calls.isEmpty())
    }

    @Test
    fun `when there are no chunks - then it answers not known without calling the model`() = runTest {
        // given
        val script = koin.get<FakeLlmScript>()
        val answerer = GroundedAnswerer(koin.get { parametersOf(script) }, relevanceThreshold = 0.6)

        // when
        val actual = answerer.answer("q", emptyList())

        // then
        assertFalse(actual.isKnown)
        assertTrue(script.calls.isEmpty())
    }

    @Test
    fun `when the top score clears the threshold - then it calls the model and returns the cited answer`() = runTest {
        // given
        val script = koin.get<FakeLlmScript>()
        script.queueText("""{"answer":"3 approvals","known":true,"citations":[{"source":"s","section":"Code Review Policy","chunk_id":1,"quote":"exactly 3 approvals"}]}""")
        val answerer = GroundedAnswerer(koin.get { parametersOf(script) }, relevanceThreshold = 0.6)

        // when
        val actual = answerer.answer("how many approvals", listOf(scored("requires exactly 3 approvals", score = 0.8)))

        // then
        assertTrue(actual.isKnown)
        assertEquals("exactly 3 approvals", actual.citations.single().quote)
        assertEquals(1, script.calls.size)
    }

    @Test
    fun `when the model errors - then it answers not known`() = runTest {
        // given — empty script → the fake returns a synthetic error result
        val answerer = GroundedAnswerer(koin.get { parametersOf(null) }, relevanceThreshold = 0.6)

        // when
        val actual = answerer.answer("q", listOf(scored("relevant text", score = 0.9)))

        // then
        assertFalse(actual.isKnown)
    }

    @Test
    fun `when answering - then the question and the context reach the model`() = runTest {
        // given
        val script = koin.get<FakeLlmScript>()
        script.queueText("""{"answer":"a","known":true,"citations":[]}""")
        val answerer = GroundedAnswerer(koin.get { parametersOf(script) }, relevanceThreshold = 0.0)

        // when
        answerer.answer("how many approvals", listOf(scored("requires exactly 3 approvals", score = 0.8)))

        // then
        val sent = script.calls.single().messages
        assertTrue(sent.any { it.role == Role.USER && "how many approvals" in it.text })
        assertTrue(sent.any { it.role == Role.SYSTEM && "requires exactly 3 approvals" in it.text })
    }

    private fun scored(text: String, score: Double): ScoredChunk =
        ScoredChunk(
            chunk = Chunk(text, ChunkMetadata(source = "s", title = "t", section = "Code Review Policy", chunkId = 1)),
            score = score,
        )
}

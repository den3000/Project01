package ru.den.writes.code.agenticHub.features.llm

import org.junit.Assume.assumeTrue
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.BuildKonfig
import ru.den.writes.code.agenticHub.features.llm.di.llmModule
import ru.den.writes.code.agenticHub.features.llm.gemini.GeminiModel
import ru.den.writes.code.agenticHub.features.rag.Retriever
import ru.den.writes.code.agenticHub.features.rag.chunking.SourceDocument
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.di.ragModule
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Opt-in live tests (see LIVE_TESTS.md): excluded unless `-PliveTests`. The plain-RAG baseline (no
// second stage) over the 10 [CONTROL_QUESTIONS], run as a 2×2 grid to make the point vivid:
//   - SMALL_HANDBOOK (clean, each answer alone in its section) → top-3 retrieval pins at 10/10;
//   - BIG_HANDBOOK   (each answer buried under decoys)         → tight top-1 pins at 1/10.
// Both across two providers (Ollama-local and the REAL Gemini API). BOTH need local Ollama up:
// retrieval always embeds via `nomic-embed-text` (`ollama pull nomic-embed-text`). The Gemini rows
// BURN TOKENS and need `GEMINI_API_KEY` (else skip). The 1/10 is a *retrieval* failure the reranker
// path recovers — see LlmWithRagRerankerAnswerLiveTest. Shared corpus/metrics live in RagLiveFixtures.
class LlmWithRagAnswerLiveTest {

    private val koin = koinApplication { modules(llmModule, ragModule, networkModule) }.koin

    @Test
    fun `when the small handbook runs through Ollama - then grounding pins at all ten`() =
        liveOllamaTest(koin) {
            assertRagComparison(ollamaApi(), SMALL_HANDBOOK, topK = 3, pinnedGrounded = 10, label = "small · ollama ${liveChatModel().id}")
        }

    @Test
    fun `when the small handbook runs through Gemini - then grounding pins at all ten`() {
        assumeTrue("GEMINI_API_KEY not set — skipping Gemini live test", BuildKonfig.GEMINI_API_KEY.isNotBlank())
        liveOllamaTest(koin) {
            assertRagComparison(geminiApi(), SMALL_HANDBOOK, topK = 3, pinnedGrounded = 10, label = "small · gemini ${GeminiModel.Default.id}")
        }
    }

    @Test
    fun `when the big handbook runs through Ollama - then grounding collapses to one`() =
        liveOllamaTest(koin) {
            assertRagComparison(ollamaApi(), BIG_HANDBOOK, topK = 1, pinnedGrounded = 1, label = "big · ollama ${liveChatModel().id}")
        }

    @Test
    fun `when the big handbook runs through Gemini - then grounding collapses to one`() {
        assumeTrue("GEMINI_API_KEY not set — skipping Gemini live test", BuildKonfig.GEMINI_API_KEY.isNotBlank())
        liveOllamaTest(koin) {
            assertRagComparison(geminiApi(), BIG_HANDBOOK, topK = 1, pinnedGrounded = 1, label = "big · gemini ${GeminiModel.Default.id}")
        }
    }

    /**
     * Plain-RAG baseline, provider-agnostic: build the index from [handbook] end-to-end (real
     * `OllamaEmbedder` via ragModule), then for every [CONTROL_QUESTIONS] entry answer WITH the
     * top-[topK] retrieved context and WITHOUT it through [llmApi]. Asserts truthfulness (both modes
     * reply) and PINS grounding at [pinnedGrounded] — the 2×2 grid's whole point is that the pin is
     * 10 on the clean handbook and 1 on the noisy one, with no reranking in between. Retrieval is
     * deterministic (same embeddings), so the pins are stable; the per-question detail is logged.
     * Call inside [liveOllamaTest].
     */
    private suspend fun assertRagComparison(
        llmApi: LlmApi,
        handbook: SourceDocument,
        topK: Int,
        pinnedGrounded: Int,
        label: String,
    ) {
        val index = koin.get<IndexingPipeline> { parametersOf(StructuralChunking()) }.index(listOf(handbook))
        val retriever = koin.get<Retriever> { parametersOf(index) }
        val params = GenerationParams(temperature = 0.0, maxTokens = 200, thinkingBudget = 0)

        val outcomes = CONTROL_QUESTIONS.map { cq ->
            val chunks = retriever.retrieve(cq.question, topK = topK)
            Outcome(
                question = cq,
                chunks = chunks,
                withRag = llmApi.send(listOf(ragChunksToContextMessage(chunks), Message(Role.USER, cq.question)), params),
                withoutRag = llmApi.send(listOf(Message(Role.USER, cq.question)), params),
            )
        }

        // truthfulness: every call must actually return an answer (the API working, not RAG quality)
        outcomes.forEach { o ->
            assertNull(o.withRag.error, "RAG mode errored on \"${o.question.question}\": ${o.withRag.error}")
            assertNull(o.withoutRag.error, "bare mode errored on \"${o.question.question}\": ${o.withoutRag.error}")
            assertTrue(!o.withRag.text.isNullOrBlank(), "RAG answer empty for \"${o.question.question}\"")
            assertTrue(!o.withoutRag.text.isNullOrBlank(), "bare answer empty for \"${o.question.question}\"")
        }

        val retrievalHits = outcomes.count { it.retrievalHit() }
        val groundedHits = outcomes.count { it.groundedHit() }
        logComparison(label, outcomes, retrievalHits, groundedHits)

        assertEquals(pinnedGrounded, groundedHits, "grounding $groundedHits/${outcomes.size} != pinned $pinnedGrounded ($label)")
    }

    private fun ollamaApi(): LlmApi =
        koin.get { parametersOf(ModelProvider.LocalOllama(model = liveChatModel())) }

    private fun geminiApi(): LlmApi =
        koin.get { parametersOf(ModelProvider.Gemini(model = GeminiModel.Default, apiKey = BuildKonfig.GEMINI_API_KEY)) }
}

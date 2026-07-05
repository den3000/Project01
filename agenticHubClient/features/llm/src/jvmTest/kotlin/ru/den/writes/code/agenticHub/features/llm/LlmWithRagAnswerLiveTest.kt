package ru.den.writes.code.agenticHub.features.llm

import org.junit.Assume.assumeTrue
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.BuildKonfig
import ru.den.writes.code.agenticHub.features.llm.di.llmModule
import ru.den.writes.code.agenticHub.features.llm.gemini.GeminiModel
import ru.den.writes.code.agenticHub.features.rag.Retriever
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.di.ragModule
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Opt-in live tests (see LIVE_TESTS.md): excluded unless `-PliveTests`. The baseline RAG-answer
// comparison — the 10 [CONTROL_QUESTIONS] answered WITH the retrieved index vs WITHOUT it — run
// through two generative providers. BOTH need local Ollama up: retrieval always embeds via
// `nomic-embed-text` (`ollama pull nomic-embed-text`). The Ollama variant also generates locally
// (default gemma4:26b, -Dollama.chat.model=<tag>); the Gemini variant generates via the REAL
// Gemini API (BURNS TOKENS, needs GEMINI_API_KEY, else skip). Shared corpus/metrics live in
// RagLiveFixtures; the reranked counterpart is LlmWithRagRerankerAnswerLiveTest.
class LlmWithRagAnswerLiveTest {

    private val koin = koinApplication { modules(llmModule, ragModule, networkModule) }.koin

    @Test
    fun `when control questions run through Ollama with the index vs without - then both modes answer and RAG is grounded`() =
        liveOllamaTest(koin) {
            // given
            val llmApi = koin.get<LlmApi> { parametersOf(ModelProvider.LocalOllama(model = liveChatModel())) }

            // when / then
            assertRagComparison(llmApi, label = "ollama ${liveChatModel().id}")
        }

    @Test
    fun `when control questions run through Gemini with the index vs without - then both modes answer and RAG is grounded`() {
        assumeTrue("GEMINI_API_KEY not set — skipping Gemini live test", BuildKonfig.GEMINI_API_KEY.isNotBlank())
        liveOllamaTest(koin) {
            // given
            val llmApi = koin.get<LlmApi> {
                parametersOf(ModelProvider.Gemini(model = GeminiModel.Default, apiKey = BuildKonfig.GEMINI_API_KEY))
            }

            // when / then
            assertRagComparison(llmApi, label = "gemini ${GeminiModel.Default.id}")
        }
    }

    /**
     * The baseline comparison, provider-agnostic: build the index end-to-end from the graph
     * (real `OllamaEmbedder` via ragModule), then for every [CONTROL_QUESTIONS] entry answer
     * WITH the retrieved context and WITHOUT it through [llmApi]. Checks three RAG-quality
     * metrics: retrieval (expected source retrieved), truthfulness (both modes reply), and
     * answer accuracy (the RAG answer carries the expected fact). Call inside [liveOllamaTest].
     */
    private suspend fun assertRagComparison(llmApi: LlmApi, label: String) {
        val index = koin.get<IndexingPipeline> { parametersOf(StructuralChunking()) }.index(listOf(HANDBOOK))
        val retriever = koin.get<Retriever> { parametersOf(index) }
        val params = GenerationParams(temperature = 0.0, maxTokens = 200, thinkingBudget = 0)

        val outcomes = CONTROL_QUESTIONS.map { cq ->
            val chunks = retriever.retrieve(cq.question, topK = 3)
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

        // RAG quality as proportions (see LIVE_TESTS.md): retrieval hit = expected source among
        // retrieved chunks; grounding hit = RAG answer carries the expected fact.
        val n = outcomes.size
        val retrievalHits = outcomes.count { it.retrievalHit() }
        val groundedHits = outcomes.count { it.groundedHit() }
        logComparison(label, outcomes, retrievalHits, groundedHits)

        assertTrue(retrievalHits >= n - MAX_MISSES, "retrieval $retrievalHits/$n below threshold (allowed $MAX_MISSES misses)")
        assertTrue(groundedHits >= n - MAX_MISSES, "grounding $groundedHits/$n below threshold (allowed $MAX_MISSES misses)")
    }
}

package ru.den.writes.code.agenticHub.features.llm

import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.llm.di.llmModule
import ru.den.writes.code.agenticHub.features.rag.Retriever
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.di.ragModule
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Opt-in live test (see LIVE_TESTS.md): excluded unless `-PliveTests`. The reranked counterpart
// of LlmWithRagAnswerLiveTest — same corpus, same 10 [CONTROL_QUESTIONS], same grounding metric.
// Shows the second RAG stage earning its keep: on the noisy handbook a plain top-K baseline lets
// the answer slip out of the window, but rewriting the query and reranking a wider pool with a
// CrossEncoder ("does this passage answer the question?") pulls it back. Needs local Ollama up
// (`ollama pull nomic-embed-text` for embeddings + a chat tag for generation); rewrite/rerank use
// the SAME local model as the answer, so the whole test is free and offline-of-the-cloud.
class LlmWithRagRerankerAnswerLiveTest {

    private val koin = koinApplication { modules(llmModule, ragModule, networkModule) }.koin

    @Test
    fun `when the same questions run reranked vs plain top-K - then rewrite plus rerank lifts grounding`() =
        liveOllamaTest(koin) {
            // given
            val llmApi = koin.get<LlmApi> { parametersOf(ModelProvider.LocalOllama(model = liveChatModel())) }

            // when / then
            assertRerankerLiftsGrounding(llmApi, label = "ollama ${liveChatModel().id}")
        }

    /**
     * Answer every [CONTROL_QUESTIONS] entry twice off the same index: once the plain way
     * (retrieve [TOP_K] by cosine, no rewrite), once reranked (rewrite the query, over-retrieve
     * [TOP_N], then [ModelReranker] down to [TOP_K_AFTER] by "does this passage answer?"). Both
     * feed the model the same number of chunks — only *which* chunks differ. Asserts the reranked
     * grounding never drops below the baseline and clears the bar (`>= n - MAX_MISSES`); the exact
     * per-run numbers are logged (RAG quality is empirical/model-dependent — see LIVE_TESTS.md).
     */
    private suspend fun assertRerankerLiftsGrounding(llmApi: LlmApi, label: String) {
        val index = koin.get<IndexingPipeline> { parametersOf(StructuralChunking()) }.index(listOf(BIG_HANDBOOK))
        val retriever = koin.get<Retriever> { parametersOf(index) }
        val rewriter = ModelQueryRewriter(llmApi)
        val reranker = ModelReranker(llmApi, threshold = RERANK_THRESHOLD, topKAfter = TOP_K_AFTER)
        val params = GenerationParams(temperature = 0.0, maxTokens = 200, thinkingBudget = 0)

        val baseline = CONTROL_QUESTIONS.map { cq ->
            val chunks = retriever.retrieve(cq.question, topK = TOP_K)
            Outcome(
                question = cq,
                chunks = chunks,
                withRag = llmApi.send(listOf(ragChunksToContextMessage(chunks), Message(Role.USER, cq.question)), params),
                withoutRag = LlmResult(text = ""),
            )
        }

        val reranked = CONTROL_QUESTIONS.map { cq ->
            val rewritten = rewriter.rewrite(cq.question)
            val candidates = retriever.retrieve(rewritten, topK = TOP_N)
            val chunks = reranker.rerank(cq.question, candidates)
            Outcome(
                question = cq,
                chunks = chunks,
                withRag = llmApi.send(listOf(ragChunksToContextMessage(chunks), Message(Role.USER, cq.question)), params),
                withoutRag = LlmResult(text = ""),
            )
        }

        // truthfulness: every RAG answer must actually come back (the pipeline working, not quality)
        (baseline + reranked).forEach { o ->
            assertNull(o.withRag.error, "RAG mode errored on \"${o.question.question}\": ${o.withRag.error}")
            assertTrue(!o.withRag.text.isNullOrBlank(), "RAG answer empty for \"${o.question.question}\"")
        }

        val n = CONTROL_QUESTIONS.size
        val baseGrounded = baseline.count { it.groundedHit() }
        val rerankGrounded = reranked.count { it.groundedHit() }
        logComparison("$label · baseline top-$TOP_K", baseline, baseline.count { it.retrievalHit() }, baseGrounded)
        logComparison("$label · reranked (rewrite+cross-encoder)", reranked, reranked.count { it.retrievalHit() }, rerankGrounded)

        assertTrue(rerankGrounded >= baseGrounded, "reranked grounding $rerankGrounded fell below baseline $baseGrounded")
        assertTrue(rerankGrounded >= n - MAX_MISSES, "reranked grounding $rerankGrounded/$n below bar (allowed $MAX_MISSES misses)")
    }

    private companion object {
        // Baseline window: the plain top-K a naive RAG turn would feed the model. Deliberately
        // tight (top-1) — with the noisy handbook a single decoy outranking the real section by
        // cosine is enough to hand the model the wrong chunk and miss the fact.
        const val TOP_K = 1

        // Reranked path: over-retrieve a wide pool (the real section survives here even when decoys
        // outscore it — with three "secret"-themed decoys the answer can sit well past top-10), then
        // reranker keeps the best TOP_K_AFTER — the SAME count the baseline feeds, so the comparison
        // isolates *which* chunk, not how many.
        const val TOP_N = 15
        const val TOP_K_AFTER = 1

        // Cutoff for "this passage doesn't answer the question" (model score in [0,1]).
        const val RERANK_THRESHOLD = 0.5
    }
}

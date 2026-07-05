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

// Opt-in live test (see LIVE_TESTS.md): excluded unless `-PliveTests`. The reranked counterpart
// of LlmWithRagAnswerLiveTest — same BIG_HANDBOOK, same 10 [CONTROL_QUESTIONS], same grounding
// metric. Shows the second RAG stage earning its keep: on the noisy handbook a plain top-K baseline
// lets the answer slip out of the window, but rewriting the query and reranking a wider pool with a
// CrossEncoder ("does this passage answer the question?") pulls it back. Retrieval always embeds via
// local Ollama (`ollama pull nomic-embed-text`). Two rows by who does the rewrite+rerank+answer:
//   - Ollama — the same local chat model; free and offline-of-the-cloud;
//   - Gemini — the REAL Gemini API for QueryRewriter + ModelReranker + answer: BURNS TOKENS (a
//     model call per candidate × question), needs `GEMINI_API_KEY`, else skip.
// Two more rows answer through [GroundedAnswerer] (mandatory sources + verbatim quotes): on the same
// noisy corpus the plain top-1 mostly returns known=false, and only the reranked path yields
// known+cited answers — citations survive only with the second stage.
class LlmWithRagRerankerAnswerLiveTest {

    private val koin = koinApplication { modules(llmModule, ragModule, networkModule) }.koin

    @Test
    fun `when reranked through Ollama - then rewrite plus rerank lifts grounding`() =
        liveOllamaTest(koin) {
            // given
            val llmApi = koin.get<LlmApi> { parametersOf(ModelProvider.LocalOllama(model = liveChatModel())) }

            // when / then
            assertRerankerLiftsGrounding(llmApi, label = "ollama ${liveChatModel().id}")
        }

    @Test
    fun `when reranked through Gemini - then rewrite plus rerank lifts grounding`() {
        assumeTrue("GEMINI_API_KEY not set — skipping Gemini live test", BuildKonfig.GEMINI_API_KEY.isNotBlank())
        liveOllamaTest(koin) {
            // given — Gemini drives QueryRewriter + ModelReranker + answer; embeddings stay local
            val llmApi = koin.get<LlmApi> {
                parametersOf(ModelProvider.Gemini(model = GeminiModel.Default, apiKey = BuildKonfig.GEMINI_API_KEY))
            }

            // when / then
            assertRerankerLiftsGrounding(llmApi, label = "gemini ${GeminiModel.Default.id}")
        }
    }

    @Test
    fun `when reranked answers are cited through Ollama - then citations survive the noisy corpus`() =
        liveOllamaTest(koin) {
            // given
            val llmApi = koin.get<LlmApi> { parametersOf(ModelProvider.LocalOllama(model = liveChatModel())) }

            // when / then
            assertRerankedCitations(llmApi, label = "ollama ${liveChatModel().id}")
        }

    @Test
    fun `when reranked answers are cited through Gemini - then citations survive the noisy corpus`() {
        assumeTrue("GEMINI_API_KEY not set — skipping Gemini live test", BuildKonfig.GEMINI_API_KEY.isNotBlank())
        liveOllamaTest(koin) {
            // given — Gemini drives QueryRewriter + ModelReranker + GroundedAnswerer; embeddings stay local
            val llmApi = koin.get<LlmApi> {
                parametersOf(ModelProvider.Gemini(model = GeminiModel.Default, apiKey = BuildKonfig.GEMINI_API_KEY))
            }

            // when / then
            assertRerankedCitations(llmApi, label = "gemini ${GeminiModel.Default.id}")
        }
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

    /**
     * Same baseline-vs-reranked shape, but answered through [GroundedAnswerer] so every reply must
     * carry mandatory sources + verbatim quotes (or say "don't know"). The subtle part: on the noisy
     * corpus the plain top-1 hands the model a *decoy*, and the model happily answers known=true and
     * cites that decoy verbatim — a citation that doesn't actually answer. So "has a citation" is not
     * enough; the graded metric is a GROUNDED citation — known + cited + the asked-for fact present in
     * the answer or a quote ([groundedCitation]). By that bar the baseline collapses (it cites decoys)
     * while the reranked path, quoting the real section, holds. Asserts reranked never drops below
     * baseline and clears `n - MAX_MISSES`; every known reranked answer must still carry a source.
     */
    private suspend fun assertRerankedCitations(llmApi: LlmApi, label: String) {
        val index = koin.get<IndexingPipeline> { parametersOf(StructuralChunking()) }.index(listOf(BIG_HANDBOOK))
        val retriever = koin.get<Retriever> { parametersOf(index) }
        val rewriter = ModelQueryRewriter(llmApi)
        val reranker = ModelReranker(llmApi, threshold = RERANK_THRESHOLD, topKAfter = TOP_K_AFTER)
        val answerer = GroundedAnswerer(llmApi, relevanceThreshold = CITE_GATE)

        val baseline = CONTROL_QUESTIONS.map { cq ->
            cq to answerer.answer(cq.question, retriever.retrieve(cq.question, topK = TOP_K))
        }
        val reranked = CONTROL_QUESTIONS.map { cq ->
            val candidates = retriever.retrieve(rewriter.rewrite(cq.question), topK = TOP_N)
            cq to answerer.answer(cq.question, reranker.rerank(cq.question, candidates))
        }

        val n = CONTROL_QUESTIONS.size
        val baseGrounded = baseline.count { groundedCitation(it.first, it.second) }
        val rerankGrounded = reranked.count { groundedCitation(it.first, it.second) }
        logCitations("$label · baseline top-$TOP_K", baseline)
        logCitations("$label · reranked (rewrite+cross-encoder)", reranked)

        // structural contract: a known answer must never come back without a source
        reranked.forEach { (cq, ans) ->
            if (ans.isKnown) assertTrue(ans.citations.isNotEmpty(), "known answer without a source for \"${cq.question}\"")
        }
        assertTrue(rerankGrounded >= baseGrounded, "reranked grounded-citations $rerankGrounded fell below baseline $baseGrounded")
        assertTrue(rerankGrounded >= n - MAX_MISSES, "reranked grounded-citations $rerankGrounded/$n below bar (allowed $MAX_MISSES misses)")
    }

    // Grounded citation = the answer is known, carries a source, AND the asked-for fact shows up in
    // the answer or one of its quotes — i.e. the citation actually answers, not just cites a decoy.
    private fun groundedCitation(cq: ControlQuestion, ans: GroundedAnswer): Boolean =
        ans.isKnown && ans.citations.isNotEmpty() && cq.expectAnyOf.any { frag ->
            frag in ans.answer.lowercase() || ans.citations.any { frag in it.quote.lowercase() }
        }

    private fun logCitations(label: String, results: List<Pair<ControlQuestion, GroundedAnswer>>) {
        val grounded = results.count { groundedCitation(it.first, it.second) }
        println("=== cited RAG ($label): grounded citations $grounded/${results.size} ===")
        results.forEach { (cq, ans) ->
            val mark = if (groundedCitation(cq, ans)) "✓" else "✗"
            val c = ans.citations.firstOrNull()
            val cite = c?.let { "[${it.source} › ${it.section} #${it.chunkId}] \"${it.quote}\"" } ?: "—"
            println("$mark [${cq.question}] known=${ans.isKnown} · $cite")
        }
    }

    private companion object {
        // Relevance gate for the cited answer: top chunk score below this → "don't know" (no model
        // call). Below both healthy cosine (baseline) and rerank (0..1) scores, so it only catches
        // genuinely empty/irrelevant context; on a baseline decoy the gate passes and the MODEL is
        // the one that reports known=false (the decoy carries no answer).
        const val CITE_GATE = 0.5

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

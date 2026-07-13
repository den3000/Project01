package ru.den.writes.code.agenticHub.features.llm

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.junit.Assume.assumeTrue
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.llm.di.llmModule
import ru.den.writes.code.agenticHub.features.llm.ollama.OllamaModel
import ru.den.writes.code.agenticHub.features.rag.Retriever
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.di.ragModule
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

// Opt-in live test (see LIVE_TESTS.md): excluded unless `-PliveTests`. Optimizing the LOCAL Ollama
// model for one task — Project Zephyr handbook Q&A over the clean [SMALL_HANDBOOK]. Retrieval is held
// fixed (same chunks feed both runs), so the ONLY variables are the generation knobs and the prompt:
//   - baseline  : provider defaults + the plain RAG grounding message;
//   - optimized : temperature 0 · top_p 0.9 · seed 42 · num_ctx 8192 · token cap · thinking off,
//                 plus a terse/cite-first SYSTEM prefix (the same shape the `zephyr-qa` profile ships).
// Grounding quality is asserted (tolerantly — RAG quality is empirical, see LIVE_TESTS.md); latency and
// tok/s are measured and logged so the before/after speed win is visible. A second test shows the seed
// making the optimized run reproducible, and a third sweeps model tags to compare quantizations.
// Everything embeds/generates through local Ollama — no cloud, no tokens.
class LlmOptimizationLiveTest {

    private val koin = koinApplication { modules(llmModule, ragModule, networkModule) }.koin

    @Test
    fun `when generation is optimized - then grounding holds and latency drops`() =
        liveOllamaTest(koin) {
            // given — one local model, one retriever over the clean handbook
            val llmApi = koin.get<LlmApi> { parametersOf(ModelProvider.LocalOllama(model = liveChatModel())) }
            val retriever = buildRetriever()
            val n = CONTROL_QUESTIONS.size

            // when — same questions, same retrieved context, two generation configs
            val baseline = runControlSet(llmApi, retriever, GenerationParams(), systemPrefix = null)
            val optimized = runControlSet(llmApi, retriever, OPTIMIZED_PARAMS, systemPrefix = OPTIMIZED_SYSTEM)

            // then — log the quality + speed comparison for the viewer
            val baseGrounded = baseline.outcomes.count { it.groundedHit() }
            val optGrounded = optimized.outcomes.count { it.groundedHit() }
            logComparison("baseline (defaults)", baseline.outcomes, baseline.outcomes.count { it.retrievalHit() }, baseGrounded)
            logComparison("optimized (temp0·seed·num_ctx·terse)", optimized.outcomes, optimized.outcomes.count { it.retrievalHit() }, optGrounded)
            logTiming("baseline", baseline, n)
            logTiming("optimized", optimized, n)

            // every turn must actually answer (pipeline health, not quality)
            (baseline.outcomes + optimized.outcomes).forEach { o ->
                assertTrue(!o.withRag.text.isNullOrBlank(), "answer empty for \"${o.question.question}\"")
            }
            // optimization must not regress grounding (tolerant — LLM variance)
            assertTrue(optGrounded >= baseGrounded - SLACK, "optimized grounding $optGrounded fell below baseline $baseGrounded")
            assertTrue(optGrounded >= n - MAX_MISSES, "optimized grounding $optGrounded/$n below bar (allowed $MAX_MISSES misses)")
        }

    @Test
    fun `when seed is fixed - then the optimized run is reproducible`() =
        liveOllamaTest(koin) {
            // given
            val llmApi = koin.get<LlmApi> { parametersOf(ModelProvider.LocalOllama(model = liveChatModel())) }
            val retriever = buildRetriever()

            // when — the same seeded config, twice
            val first = runControlSet(llmApi, retriever, OPTIMIZED_PARAMS, systemPrefix = OPTIMIZED_SYSTEM)
            val second = runControlSet(llmApi, retriever, OPTIMIZED_PARAMS, systemPrefix = OPTIMIZED_SYSTEM)

            // then — with temperature 0 + a fixed seed the wording should repeat; log the exact-match rate
            val identical = first.outcomes.zip(second.outcomes).count { (a, b) -> a.withRag.text == b.withRag.text }
            println("=== stability: $identical/${CONTROL_QUESTIONS.size} answers identical across two seed=$SEED runs ===")

            // robust determinism signal: the same set of facts is grounded both times (wording may drift a token)
            val g1 = first.outcomes.count { it.groundedHit() }
            val g2 = second.outcomes.count { it.groundedHit() }
            assertEquals(g1, g2, "seeded runs grounded a different number of facts ($g1 vs $g2)")
        }

    @Test
    fun `when comparing model quantizations - then each grounds and timing is logged`() =
        liveOllamaTest(koin) {
            // given — keep only the candidate tags actually pulled locally
            val pulled = koin.get<HttpClient>().get("$OLLAMA_BASE/api/tags").bodyAsText()
            val available = CANDIDATE_MODELS.filter { it in pulled }
            assumeTrue("none of $CANDIDATE_MODELS pulled — `ollama pull <tag>` to compare", available.isNotEmpty())
            val retriever = buildRetriever()
            val n = CONTROL_QUESTIONS.size

            // when / then — run the optimized config on each tag, log the quality vs speed trade-off
            available.forEach { tag ->
                val llmApi = koin.get<LlmApi> { parametersOf(ModelProvider.LocalOllama(model = OllamaModel.fromId(tag))) }
                val run = runControlSet(llmApi, retriever, OPTIMIZED_PARAMS, systemPrefix = OPTIMIZED_SYSTEM)
                val grounded = run.outcomes.count { it.groundedHit() }
                logComparison("quant $tag", run.outcomes, run.outcomes.count { it.retrievalHit() }, grounded)
                logTiming("quant $tag", run, n)
                assertTrue(grounded >= n - MAX_MISSES, "$tag grounding $grounded/$n below bar (allowed $MAX_MISSES misses)")
            }
        }

    // Index the clean handbook once and hand back a retriever; retrieval embeds locally via Ollama and is
    // identical for every config, so only generation differs between the compared runs.
    private suspend fun buildRetriever(): Retriever {
        val index = koin.get<IndexingPipeline> { parametersOf(StructuralChunking()) }.index(listOf(SMALL_HANDBOOK))
        return koin.get<Retriever> { parametersOf(index) }
    }

    // Answer every control question with one config, timing each call and folding the reply into an [Outcome].
    private suspend fun runControlSet(
        llmApi: LlmApi,
        retriever: Retriever,
        params: GenerationParams,
        systemPrefix: Message?,
    ): ModeRun {
        var totalMs = 0L
        var outputTokens = 0
        val outcomes = CONTROL_QUESTIONS.map { cq ->
            val chunks = retriever.retrieve(cq.question, topK = TOP_K)
            val messages = buildList {
                systemPrefix?.let { add(it) }
                add(ragChunksToContextMessage(chunks))
                add(Message(role = Role.USER, text = cq.question))
            }
            val (result, dur) = measureTimedValue { llmApi.send(messages, params) }
            totalMs += dur.inWholeMilliseconds
            outputTokens += result.usage?.outputTokens ?: 0
            Outcome(question = cq, chunks = chunks, withRag = result, withoutRag = LlmResult(text = ""))
        }
        return ModeRun(outcomes, totalMs, outputTokens)
    }

    private fun logTiming(label: String, run: ModeRun, n: Int) {
        val secs = run.totalMs / 1000.0
        val tokPerSec = if (secs > 0) run.outputTokens / secs else 0.0
        println("--- timing ($label): total %.1fs · %d output tok · %.1f tok/s · avg %.2fs/q ---".format(secs, run.outputTokens, tokPerSec, secs / n))
    }

    private data class ModeRun(val outcomes: List<Outcome>, val totalMs: Long, val outputTokens: Int)

    private companion object {
        // Clean handbook grounds well at a small top-K — the point here is the generation knobs, not retrieval.
        const val TOP_K = 3
        const val SEED = 42

        // Tolerance so ordinary LLM variance between the two runs doesn't fail the test.
        const val SLACK = 1

        // The optimized config: deterministic (temp 0 + seed), nucleus-capped, a tuned context window, a
        // one-sentence token cap, and thinking off so the whole cap goes to the answer (not the reasoning trace).
        val OPTIMIZED_PARAMS = GenerationParams(
            temperature = 0.0,
            topP = 0.9,
            seed = SEED,
            contextWindow = 8192,
            maxTokens = 200,
            thinkingBudget = 0,
        )

        // The task-specific prompt (the same shape the `zephyr-qa` profile ships as a [Profile] block).
        val OPTIMIZED_SYSTEM = Message(
            role = Role.SYSTEM,
            text = "Answer in a single sentence using ONLY the provided context. State the exact figure and " +
                "cite the [source: …] tag you used. No preamble and do not restate the question.",
        )

        // Quantization sweep candidates — different tags/quant levels of the local models. Only the ones
        // actually pulled are run (per-tag skip); pull more with `ollama pull <tag>` to widen the comparison.
        val CANDIDATE_MODELS = listOf("gemma4:latest", "gemma4:26b", "qwen3.5:latest")
    }
}

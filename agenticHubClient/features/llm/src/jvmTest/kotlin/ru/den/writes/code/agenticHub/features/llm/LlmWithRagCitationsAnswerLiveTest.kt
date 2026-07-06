package ru.den.writes.code.agenticHub.features.llm

import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.llm.di.llmModule
import ru.den.writes.code.agenticHub.features.rag.Retriever
import ru.den.writes.code.agenticHub.features.rag.chunking.SourceDocument
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.di.ragModule
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline
import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Opt-in live test (see LIVE_TESTS.md): excluded unless `-PliveTests`. The anti-hallucination
// deliverable end-to-end over the clean SMALL_HANDBOOK: every answer must be KNOWN, carry ≥1
// citation, and each citation's quote must appear VERBATIM in a retrieved chunk (not paraphrased or
// invented) — plus the asked-for fact shows up in the answer or a quote. A second test fires the
// low-relevance gate: an off-topic question must yield "don't know, please clarify". Ollama-local
// (`ollama pull nomic-embed-text` + a chat tag); free, no cloud.
class LlmWithRagCitationsAnswerLiveTest {

    private val koin = koinApplication { modules(llmModule, ragModule, networkModule) }.koin

    @Test
    fun `when questions run with grounded answers - then every answer is known cited and quoted from context`() =
        liveOllamaTest(koin) {
            val answerer = GroundedAnswerer(ollamaApi(), relevanceThreshold = GATE_THRESHOLD)
            val retriever = retrieverOver(SMALL_HANDBOOK)

            CONTROL_QUESTIONS.forEach { cq ->
                val chunks = retriever.retrieve(cq.question, topK = TOP_K)
                val ans = answerer.answer(cq.question, chunks)

                println("\n[${cq.question}]")
                println("  known=${ans.isKnown} · citations=${ans.citations.size} · answer: ${ans.answer.trim().replace("\n", " ")}")
                ans.citations.forEach { println("  cite [${it.source} › ${it.section} #${it.chunkId}] \"${it.quote}\"") }

                assertTrue(ans.isKnown, "answer not known for \"${cq.question}\"")
                assertTrue(ans.citations.isNotEmpty(), "no citations for \"${cq.question}\"")
                ans.citations.forEach { c ->
                    assertTrue(quoteIsVerbatim(c.quote, chunks), "quote not verbatim in context for \"${cq.question}\": \"${c.quote}\"")
                }
                val factPresent = cq.expectAnyOf.any { frag ->
                    frag in ans.answer.lowercase() || ans.citations.any { frag in it.quote.lowercase() }
                }
                assertTrue(factPresent, "expected fact ${cq.expectAnyOf} missing from answer and quotes for \"${cq.question}\"")
            }
        }

    @Test
    fun `when an off-topic question is asked - then it says it does not know and asks to clarify`() =
        liveOllamaTest(koin) {
            val answerer = GroundedAnswerer(ollamaApi(), relevanceThreshold = GATE_THRESHOLD)
            val retriever = retrieverOver(SMALL_HANDBOOK)

            val question = "What is the capital of France?"
            val chunks = retriever.retrieve(question, topK = TOP_K)
            val ans = answerer.answer(question, chunks)
            println("\n[off-topic] known=${ans.isKnown} · answer: ${ans.answer.trim().replace("\n", " ")}")

            assertFalse(ans.isKnown, "off-topic answer should be not-known")
            assertTrue(ans.citations.isEmpty(), "off-topic answer should carry no citations")
            assertTrue(
                CLARIFY_MARKERS.any { it in ans.answer.lowercase() },
                "off-topic answer should ask to clarify / say it doesn't know: ${ans.answer}",
            )
        }

    private suspend fun retrieverOver(handbook: SourceDocument): Retriever {
        val index = koin.get<IndexingPipeline> { parametersOf(StructuralChunking()) }.index(listOf(handbook))
        return koin.get { parametersOf(index) }
    }

    private fun ollamaApi(): LlmApi =
        koin.get { parametersOf(ModelProvider.LocalOllama(model = liveChatModel())) }

    // Verbatim check (whitespace-normalized): the quote must actually appear in one retrieved chunk.
    private fun quoteIsVerbatim(quote: String, chunks: List<ScoredChunk>): Boolean {
        val q = normalize(quote)
        return q.isNotBlank() && chunks.any { normalize(it.chunk.text).contains(q) }
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val TOP_K = 3

        // Gate on retrieval cosine: clean on-topic sections score well above this; an off-topic
        // question lands below it (or the model itself reports known=false on irrelevant context).
        const val GATE_THRESHOLD = 0.5

        val CLARIFY_MARKERS = listOf("clarify", "rephrase", "don't know", "do not know", "don't have")
    }
}

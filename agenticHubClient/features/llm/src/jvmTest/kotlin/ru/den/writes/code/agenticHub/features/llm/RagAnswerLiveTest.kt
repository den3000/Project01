package ru.den.writes.code.agenticHub.features.llm

import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.llm.di.llmModule
import ru.den.writes.code.agenticHub.features.rag.Retriever
import ru.den.writes.code.agenticHub.features.rag.chunking.SourceDocument
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.di.ragModule
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Opt-in live test (see LIVE_TESTS.md): excluded unless `-PliveTests`. Needs a live Ollama
// with BOTH a generative tag (default gemma4:26b, -Dollama.chat.model=<tag>) and the embed
// model (`ollama pull nomic-embed-text`). The "first RAG query" comparison — the same question
// answered WITH the retrieved index vs WITHOUT it — wired end-to-end from the real production
// graph (llmModule + ragModule over networkModule's HttpClient). The formal 10-question suite is
// a later deliverable; this scaffolds the comparison on a few control questions.
class RagAnswerLiveTest {

    private val koin = koinApplication { modules(llmModule, ragModule, networkModule) }.koin

    @Test
    fun `when control questions run with the index vs without - then both modes answer and RAG is grounded`() =
        liveOllamaTest(koin) {
            // given
            val chatApi = koin.get<LlmApi> { parametersOf(ModelProvider.LocalOllama(model = liveChatModel())) }
            val index = koin.get<IndexingPipeline> { parametersOf(StructuralChunking()) }.index(listOf(HANDBOOK))
            val retriever = koin.get<Retriever> { parametersOf(index) }
            val params = GenerationParams(temperature = 0.0, maxTokens = 160, thinkingBudget = 0)

            // when — answer every control question WITH the retrieved context and WITHOUT it
            val comparisons = CONTROL_QUESTIONS.map { question ->
                val chunks = retriever.retrieve(question, topK = 2)
                Comparison(
                    question = question,
                    topSection = chunks.firstOrNull()?.chunk?.metadata?.section,
                    topScore = chunks.firstOrNull()?.score ?: 0.0,
                    withRag = chatApi.send(listOf(ragChunksToContextMessage(chunks), Message(Role.USER, question)), params),
                    withoutRag = chatApi.send(listOf(Message(Role.USER, question)), params),
                )
            }

            // then — both modes answer, and the RAG answer surfaces the grounded fact (3 approvals)
            comparisons.forEachIndexed { i, c ->
                assertNull(c.withRag.error, "RAG mode errored on q$i: ${c.withRag.error}")
                assertNull(c.withoutRag.error, "bare mode errored on q$i: ${c.withoutRag.error}")
                assertTrue(!c.withRag.text.isNullOrBlank(), "RAG answer empty on q$i")
                assertTrue(!c.withoutRag.text.isNullOrBlank(), "bare answer empty on q$i")
            }
            val groundedAnswer = comparisons.first().withRag.text.orEmpty().lowercase()
            assertTrue(
                "3" in groundedAnswer || "three" in groundedAnswer,
                "RAG answer should surface the grounded '3 approvals' fact, was: $groundedAnswer",
            )
            logComparison(comparisons)
        }

    private fun logComparison(comparisons: List<Comparison>) {
        println("=== RAG comparison (model=${liveChatModel().id}) ===")
        comparisons.forEachIndexed { i, c ->
            println("\n[Q${i + 1}] ${c.question}")
            println("  top chunk: ${c.topSection} (score=%.3f)".format(c.topScore))
            println("  no-RAG : ${c.withoutRag.text?.trim()?.replace("\n", " ")}")
            println("  + RAG  : ${c.withRag.text?.trim()?.replace("\n", " ")}")
        }
    }

    private data class Comparison(
        val question: String,
        val topSection: String?,
        val topScore: Double,
        val withRag: LlmResult,
        val withoutRag: LlmResult,
    )

    private companion object {
        // A small, fictional internal handbook — facts the base model cannot know, so the
        // difference between the RAG and no-RAG answers is unambiguous.
        val HANDBOOK = SourceDocument(
            source = "handbook/zephyr.md",
            title = "Project Zephyr — Engineering Handbook",
            text = """
                # Project Zephyr — Engineering Handbook

                ## Code Review Policy
                Every merge request in Project Zephyr requires exactly 3 approvals before it can be
                merged. The maximum review turnaround (SLA) is 12 hours.

                ## Deployment Windows
                Production deploys for Project Zephyr are permitted only on Tuesdays and Thursdays,
                between 10:00 and 12:00 UTC. Deploys outside this window need VP sign-off.

                ## On-call Rotation
                The Project Zephyr on-call rotation lasts 5 days and is handed over every Monday at
                09:00 UTC.
            """.trimIndent(),
        )

        val CONTROL_QUESTIONS = listOf(
            "How many approvals does a Project Zephyr merge request require before merging?",
            "On which days is production deployment allowed for Project Zephyr?",
            "How long does the Project Zephyr on-call rotation last?",
        )
    }
}

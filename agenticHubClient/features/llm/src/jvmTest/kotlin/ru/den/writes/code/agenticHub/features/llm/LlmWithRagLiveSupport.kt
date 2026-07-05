package ru.den.writes.code.agenticHub.features.llm

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf
import ru.den.writes.code.agenticHub.features.llm.ollama.OllamaModel
import ru.den.writes.code.agenticHub.features.rag.Retriever
import ru.den.writes.code.agenticHub.features.rag.chunking.SourceDocument
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val OLLAMA_BASE = "http://localhost:11434"

/**
 * Shared harness for the "LLM answers over a RAG index" `*LiveTest` classes (see
 * LIVE_TESTS.md): run [block] as a coroutine test, but skip it (JUnit [assumeTrue])
 * when the local Ollama at [OLLAMA_BASE] isn't reachable — needed by EVERY variant,
 * because retrieval always embeds through Ollama (`nomic-embed-text`), even when the
 * generative model is a cloud provider. The [koin] graph supplies the probe [HttpClient].
 */
internal fun liveOllamaTest(koin: Koin, block: suspend () -> Unit): TestResult = runTest {
    assumeOllamaUp(koin.get())
    block()
}

private suspend fun assumeOllamaUp(client: HttpClient) {
    val reachable = try {
        client.get("$OLLAMA_BASE/api/tags").status.isSuccess()
    } catch (_: Exception) {
        false
    }
    assumeTrue("Ollama not reachable at $OLLAMA_BASE — skipping live test", reachable)
}

/**
 * The generative Ollama tag the local-model variant hits. Overridable so a run can
 * target whatever model is pulled locally (`-Dollama.chat.model=gemma4:31b`); defaults
 * to [OllamaModel.Default]. Prerequisite for a non-skipped run: `ollama pull <this tag>`.
 */
internal fun liveChatModel(): OllamaModel =
    System.getProperty("ollama.chat.model")?.let(OllamaModel::fromId) ?: OllamaModel.Default

/**
 * The día-22 "first RAG query" comparison, provider-agnostic: build the index end-to-end
 * from the graph (real `OllamaEmbedder` via ragModule), then answer every control question
 * WITH the retrieved context and WITHOUT it through [chatApi]. Asserts both modes reply and
 * that the grounded fact (3 approvals) surfaces in the RAG answer. [label] names the provider
 * in the printed comparison. Call inside [liveOllamaTest].
 */
internal suspend fun assertRagComparison(koin: Koin, chatApi: LlmApi, label: String) {
    val index = koin.get<IndexingPipeline> { parametersOf(StructuralChunking()) }.index(listOf(HANDBOOK))
    val retriever = koin.get<Retriever> { parametersOf(index) }
    val params = GenerationParams(temperature = 0.0, maxTokens = 160, thinkingBudget = 0)

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

    comparisons.forEachIndexed { i, c ->
        assertNull(c.withRag.error, "RAG mode errored on q$i: ${c.withRag.error}")
        assertNull(c.withoutRag.error, "bare mode errored on q$i: ${c.withoutRag.error}")
        assertTrue(!c.withRag.text.isNullOrBlank(), "RAG answer empty on q$i")
        assertTrue(!c.withoutRag.text.isNullOrBlank(), "bare answer empty on q$i")
    }
    val grounded = comparisons.first().withRag.text.orEmpty().lowercase()
    assertTrue("3" in grounded || "three" in grounded, "RAG answer should surface '3 approvals', was: $grounded")
    logComparison(label, comparisons)
}

private fun logComparison(label: String, comparisons: List<Comparison>) {
    println("=== RAG comparison ($label) ===")
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

// A small, fictional internal handbook — facts the base model cannot know, so the
// difference between the RAG and no-RAG answers is unambiguous. Shared by every variant.
internal val HANDBOOK = SourceDocument(
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

internal val CONTROL_QUESTIONS = listOf(
    "How many approvals does a Project Zephyr merge request require before merging?",
    "On which days is production deployment allowed for Project Zephyr?",
    "How long does the Project Zephyr on-call rotation last?",
)

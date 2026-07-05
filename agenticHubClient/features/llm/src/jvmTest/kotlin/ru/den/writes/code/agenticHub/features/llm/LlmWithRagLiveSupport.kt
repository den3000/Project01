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
import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

internal const val OLLAMA_BASE = "http://localhost:11434"

// Real inference over 10 questions × 2 modes blows past runTest's 60s default — this is
// wall-clock (real HTTP), not virtual time, so give it a generous ceiling.
private val LIVE_TIMEOUT = 15.minutes

// Allow up to this many misses out of the control set before a metric is considered failed
// (RAG quality is a "proportion of relevant hits", not all-or-nothing — see LIVE_TESTS.md).
private const val MAX_MISSES = 2

/**
 * Shared harness for the "LLM answers over a RAG index" `*LiveTest` classes (see
 * LIVE_TESTS.md): run [block] as a coroutine test, but skip it (JUnit [assumeTrue])
 * when the local Ollama at [OLLAMA_BASE] isn't reachable — needed by EVERY variant,
 * because retrieval always embeds through Ollama (`nomic-embed-text`), even when the
 * generative model is a cloud provider. The [koin] graph supplies the probe [HttpClient].
 */
internal fun liveOllamaTest(koin: Koin, block: suspend () -> Unit): TestResult = runTest(timeout = LIVE_TIMEOUT) {
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
 * One control question in the evaluation set: the [question] itself, [expectedSection]
 * — the handbook section retrieval SHOULD surface for it (the "which source" fixation) —
 * and [expectAnyOf] — lowercase fragments, at least one of which the grounded answer
 * SHOULD contain (the "what should be in the answer" fixation). Number facts list both
 * digit and word forms so a paraphrase ("three") still counts.
 */
internal data class ControlQuestion(
    val question: String,
    val expectedSection: String,
    val expectAnyOf: List<String>,
)

/**
 * The día-22 comparison, provider-agnostic: build the index end-to-end from the graph
 * (real `OllamaEmbedder` via ragModule), then for every [CONTROL_QUESTIONS] entry answer
 * WITH the retrieved context and WITHOUT it through [chatApi]. Per question it checks three
 * RAG-quality metrics: retrieval (expected source is retrieved), truthfulness (both modes
 * reply), and answer accuracy (the RAG answer carries the expected fact). [label] names the
 * provider in the printed comparison. Call inside [liveOllamaTest].
 */
internal suspend fun assertRagComparison(koin: Koin, chatApi: LlmApi, label: String) {
    val index = koin.get<IndexingPipeline> { parametersOf(StructuralChunking()) }.index(listOf(HANDBOOK))
    val retriever = koin.get<Retriever> { parametersOf(index) }
    val params = GenerationParams(temperature = 0.0, maxTokens = 200, thinkingBudget = 0)

    val outcomes = CONTROL_QUESTIONS.map { cq ->
        val chunks = retriever.retrieve(cq.question, topK = 3)
        Outcome(
            question = cq,
            chunks = chunks,
            withRag = chatApi.send(listOf(ragChunksToContextMessage(chunks), Message(Role.USER, cq.question)), params),
            withoutRag = chatApi.send(listOf(Message(Role.USER, cq.question)), params),
        )
    }

    // truthfulness: every call must actually return an answer (this is about the API working,
    // not RAG quality) — hard per question.
    outcomes.forEach { o ->
        assertNull(o.withRag.error, "RAG mode errored on \"${o.question.question}\": ${o.withRag.error}")
        assertNull(o.withoutRag.error, "bare mode errored on \"${o.question.question}\": ${o.withoutRag.error}")
        assertTrue(!o.withRag.text.isNullOrBlank(), "RAG answer empty for \"${o.question.question}\"")
        assertTrue(!o.withoutRag.text.isNullOrBlank(), "bare answer empty for \"${o.question.question}\"")
    }

    // RAG quality as proportions (see LIVE_TESTS.md): retrieval hit = expected source among
    // the retrieved chunks; grounding hit = RAG answer carries the expected fact.
    val n = outcomes.size
    val retrievalHits = outcomes.count { it.retrievalHit() }
    val groundedHits = outcomes.count { it.groundedHit() }
    logComparison(label, outcomes, retrievalHits, groundedHits)

    assertTrue(retrievalHits >= n - MAX_MISSES, "retrieval $retrievalHits/$n below threshold (allowed $MAX_MISSES misses)")
    assertTrue(groundedHits >= n - MAX_MISSES, "grounding $groundedHits/$n below threshold (allowed $MAX_MISSES misses)")
}

private fun Outcome.retrievalHit(): Boolean =
    question.expectedSection in chunks.mapNotNull { it.chunk.metadata.section }

private fun Outcome.groundedHit(): Boolean =
    question.expectAnyOf.any { it in withRag.text.orEmpty().lowercase() }

private fun logComparison(label: String, outcomes: List<Outcome>, retrievalHits: Int, groundedHits: Int) {
    val n = outcomes.size
    println("=== RAG comparison ($label): retrieval $retrievalHits/$n, grounded $groundedHits/$n ===")
    outcomes.forEachIndexed { i, o ->
        val top = o.chunks.firstOrNull()
        val r = if (o.retrievalHit()) "✓" else "✗"
        val g = if (o.groundedHit()) "✓" else "✗"
        println("\n[Q${i + 1}] ${o.question.question}")
        println("  expect source=${o.question.expectedSection} · answer∋${o.question.expectAnyOf}")
        println("  retrieval $r  top=${top?.chunk?.metadata?.section} (score=%.3f) · grounded $g".format(top?.score ?: 0.0))
        println("  no-RAG : ${o.withoutRag.text?.trim()?.replace("\n", " ")}")
        println("  + RAG  : ${o.withRag.text?.trim()?.replace("\n", " ")}")
    }
}

private data class Outcome(
    val question: ControlQuestion,
    val chunks: List<ScoredChunk>,
    val withRag: LlmResult,
    val withoutRag: LlmResult,
)

// A fictional internal handbook — facts the base model cannot know, so the difference
// between the RAG and no-RAG answers is unambiguous. One H2 section per fact; shared by
// every variant. StructuralChunking maps each `## Heading` to a chunk's metadata.section.
internal val HANDBOOK = SourceDocument(
    source = "handbook/zephyr.md",
    title = "Project Zephyr — Engineering Handbook",
    text = """
        # Project Zephyr — Engineering Handbook

        ## Code Review Policy
        Every merge request in Project Zephyr requires exactly 3 approvals before it can be
        merged. The maximum review turnaround (SLA) is 12 hours.

        ## Deployment Windows
        Production deploys are permitted only on Tuesdays and Thursdays, between 10:00 and
        12:00 UTC. Deploys outside this window need VP sign-off.

        ## On-Call Rotation
        The on-call rotation lasts 5 days and is handed over every Monday at 09:00 UTC.

        ## Incident Severity
        There are four severity levels. A SEV-1 incident must receive a first response
        within 15 minutes; lower severities are handled best-effort.

        ## Branching Model
        Project Zephyr is trunk-based: a feature branch must merge within 2 days, and every
        branch uses the `zephyr/` name prefix.

        ## Secrets Management
        All secrets live in HashiCorp Vault and are rotated every 90 days. Plaintext secrets
        committed to source control are forbidden.

        ## Data Retention
        Application logs are retained for 30 days; database backups are kept for 1 year.

        ## Release Cadence
        A new version ships every two weeks, following the CalVer versioning scheme.

        ## Testing Requirements
        A merge is blocked below 80% line coverage on the changed modules.

        ## Support SLA
        Production support must acknowledge a ticket within 4 hours, with a resolution
        target of 3 business days.
    """.trimIndent(),
)

// The 10 control questions with their fixed expectations (source + expected answer fragment).
internal val CONTROL_QUESTIONS = listOf(
    ControlQuestion(
        "How many approvals does a Project Zephyr merge request require before merging?",
        expectedSection = "Code Review Policy",
        expectAnyOf = listOf("3 approval", "three approval"),
    ),
    ControlQuestion(
        "On which days is production deployment allowed for Project Zephyr?",
        expectedSection = "Deployment Windows",
        expectAnyOf = listOf("tuesday"),
    ),
    ControlQuestion(
        "How long does the Project Zephyr on-call rotation last?",
        expectedSection = "On-Call Rotation",
        expectAnyOf = listOf("5 day", "five day"),
    ),
    ControlQuestion(
        "What is the first-response time for a SEV-1 incident?",
        expectedSection = "Incident Severity",
        expectAnyOf = listOf("15 minute", "15 min", "fifteen minute"),
    ),
    ControlQuestion(
        "What is the maximum lifetime of a feature branch before it must be merged?",
        expectedSection = "Branching Model",
        expectAnyOf = listOf("2 day", "two day"),
    ),
    ControlQuestion(
        "How often are secrets rotated in Project Zephyr?",
        expectedSection = "Secrets Management",
        expectAnyOf = listOf("90 day", "ninety day"),
    ),
    ControlQuestion(
        "How long are application logs retained?",
        expectedSection = "Data Retention",
        expectAnyOf = listOf("30 day", "thirty day"),
    ),
    ControlQuestion(
        "What is the release cadence for Project Zephyr — how often is a new release shipped?",
        expectedSection = "Release Cadence",
        expectAnyOf = listOf("two week", "2 week", "biweek", "every two"),
    ),
    ControlQuestion(
        "What is the minimum line coverage required to merge?",
        expectedSection = "Testing Requirements",
        expectAnyOf = listOf("80"),
    ),
    ControlQuestion(
        "Within how long must production support acknowledge a ticket?",
        expectedSection = "Support SLA",
        expectAnyOf = listOf("4 hour", "four hour"),
    ),
)

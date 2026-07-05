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
import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Opt-in live tests (see LIVE_TESTS.md): excluded unless `-PliveTests`. The día-22 "first RAG
// query" comparison — 10 control questions answered WITH the retrieved index vs WITHOUT it —
// run through two generative providers. BOTH need local Ollama up: retrieval always embeds via
// `nomic-embed-text` (`ollama pull nomic-embed-text`). The Ollama variant also generates locally
// (default gemma4:26b, -Dollama.chat.model=<tag>); the Gemini variant generates via the REAL
// Gemini API (BURNS TOKENS, needs GEMINI_API_KEY, else skip). Shared probe/model helpers live in
// LlmWithRagLiveSupport; the comparison + control set are file-local below.
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
     * The día-22 comparison, provider-agnostic: build the index end-to-end from the graph
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

    private data class ControlQuestion(
        val question: String,
        val expectedSection: String,
        val expectAnyOf: List<String>,
    )

    private data class Outcome(
        val question: ControlQuestion,
        val chunks: List<ScoredChunk>,
        val withRag: LlmResult,
        val withoutRag: LlmResult,
    )

    private companion object {
        // Allow up to this many misses out of the control set before a metric fails (RAG quality
        // is a "proportion of relevant hits", not all-or-nothing — see LIVE_TESTS.md).
        const val MAX_MISSES = 2

        // A fictional internal handbook — facts the base model cannot know, so the difference
        // between the RAG and no-RAG answers is unambiguous. One H2 section per fact;
        // StructuralChunking maps each `## Heading` to a chunk's metadata.section.
        val HANDBOOK = SourceDocument(
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
        val CONTROL_QUESTIONS = listOf(
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
    }
}

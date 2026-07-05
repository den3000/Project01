package ru.den.writes.code.agenticHub.features.llm

import ru.den.writes.code.agenticHub.features.rag.chunking.SourceDocument
import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk

// Shared fixtures for the RAG-answer live tests (baseline and reranked): one fictional
// internal handbook, a fixed control set of questions with their expectations, and the
// RAG-quality metrics. Kept in one place so both tests grade the same corpus the same way.

// Allow up to this many misses out of the control set before a metric fails (RAG quality
// is a "proportion of relevant hits", not all-or-nothing — see LIVE_TESTS.md).
internal const val MAX_MISSES = 2

internal data class ControlQuestion(
    val question: String,
    val expectedSection: String,
    val expectAnyOf: List<String>,
)

internal data class Outcome(
    val question: ControlQuestion,
    val chunks: List<ScoredChunk>,
    val withRag: LlmResult,
    val withoutRag: LlmResult,
)

// retrieval hit = the expected source section is among the retrieved chunks.
internal fun Outcome.retrievalHit(): Boolean =
    question.expectedSection in chunks.mapNotNull { it.chunk.metadata.section }

// grounding hit = the RAG answer carries the expected fact.
internal fun Outcome.groundedHit(): Boolean =
    question.expectAnyOf.any { it in withRag.text.orEmpty().lowercase() }

internal fun logComparison(label: String, outcomes: List<Outcome>, retrievalHits: Int, groundedHits: Int) {
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

// A fictional internal handbook — facts the base model cannot know, so the difference
// between the RAG and no-RAG answers is unambiguous. One H2 section per fact;
// StructuralChunking maps each `## Heading` to a chunk's metadata.section.
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

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

// Two versions of a fictional internal handbook — facts the base model cannot know, so the
// difference between the RAG and no-RAG answers is unambiguous. One H2 section per fact;
// StructuralChunking maps each `## Heading` to a chunk's metadata.section.
//
// SMALL_HANDBOOK: just the 10 authoritative sections, each answer in its own clean section. Nothing
// competes with it, so plain top-K retrieval already scores 10/10 — the "RAG works, no second stage
// needed" case.
//
// BIG_HANDBOOK: the same 10 answers drowned in [NOISE_SECTIONS] — for every answer two non-answering
// decoys (a topical sibling with the same nouns, and a question-mirroring sibling that echoes the
// very phrasing "how long / how often / minimum … required"), all stating NO number. By cosine those
// decoys sit at or above the real section, so a tight top-1 retrieval hands the model a decoy and the
// answer slips out of the window — the baseline collapses to ~1/10. Over-retrieving a wider top-N and
// reranking by "does this passage actually answer?" (a CrossEncoder reads passage+question jointly,
// which cosine cannot) pulls the real section back — see LlmWithRagRerankerAnswerLiveTest.
internal val SMALL_HANDBOOK = SourceDocument(
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

// Two decoys per answer (10 topical + 10 question-mirroring), appended to SMALL_HANDBOOK to make
// BIG_HANDBOOK. None of them states a number — they only sit close to the question in vector space.
private val NOISE_SECTIONS = """
    ## Code Review Culture
    Project Zephyr values thorough code review. Reviewers leave inline comments on a merge
    request, request changes, and approve once their concerns are resolved. Good reviews
    weigh correctness and readability over style nits, and mentor newer engineers.

    ## Deployment Pipeline
    The production deployment pipeline builds an artifact, runs smoke tests, and promotes
    the release. Every production deploy is logged and announced in the release channel.

    ## On-Call Responsibilities
    The on-call engineer watches dashboards, acknowledges pages, and coordinates the
    response. On-call duties are shared fairly across the rotation and tracked in the
    scheduling tool.

    ## Incident Response
    When a SEV-1 incident is declared, the responder opens a channel, assigns a scribe, and
    drives the incident toward mitigation. A postmortem follows every SEV-1.

    ## Feature Development
    Feature work starts from a short design note. Engineers open a feature branch, push
    early, and keep the change small so the review of the branch stays quick.

    ## Secret Storage
    Project Zephyr keeps its secrets out of source control. Application code reads each
    secret from the Vault agent at runtime; nothing sensitive is baked into the image.

    ## Logging Practices
    Application logs are shipped to the central log store and searchable by request id.
    Structured logging is required so dashboards and alerts can be built on the logs.

    ## Versioning Scheme
    Project Zephyr uses CalVer for its version numbers. Each release is tagged, a changelog
    is generated, and the artifact is published to the internal registry.

    ## Test Strategy
    Tests span unit, integration, and end-to-end layers. Line coverage is measured per
    module and reported on every merge request so gaps are visible.

    ## Support Process
    Production support triages incoming tickets, tags them by severity, and routes each
    ticket to the owning team. An acknowledged ticket gets status updates until resolved.

    ## Merge Requirements
    Before a Project Zephyr merge request can be merged it must pass the required checks and
    gather the necessary approvals from reviewers. The exact bar is enforced by branch
    protection, not by convention.

    ## Production Deployment
    Whether a production deployment is allowed on a given day for Project Zephyr depends on the
    deployment calendar and any active change freeze. Check the calendar before you ship.

    ## Rotation Schedule
    How long each Project Zephyr on-call rotation lasts is defined in the rotation schedule and
    can vary by team. The scheduling tool is the source of truth for handoff timing.

    ## Response Times
    The first-response time for a SEV-1 incident is tracked against the incident SLA. Response
    timers start the moment the incident is declared and stop at first human acknowledgement.

    ## Branch Lifetime
    The maximum lifetime of a feature branch before it must be merged is governed by the
    trunk-based policy — long-lived branches are discouraged and flagged by tooling.

    ## Secret Rotation
    How often secrets are rotated in Project Zephyr is set by the security policy and enforced
    by Vault. Rotation is automated so engineers never handle raw credentials.

    ## Log Retention
    How long application logs are retained in Project Zephyr is defined by the data-retention
    policy and may differ between environments. Retention is enforced by the log store.

    ## Shipping Cadence
    The release cadence for Project Zephyr — how often a new release is shipped — follows the
    team roadmap and the CalVer tags. Releases go out on a regular schedule.

    ## Coverage Gate
    The minimum line coverage required to merge in Project Zephyr is enforced by the CI coverage
    gate on the changed modules. Merges that lower coverage are blocked automatically.

    ## Acknowledgement SLA
    Within how long production support must acknowledge a ticket is defined by the support SLA
    and tracked per ticket. The clock starts when the ticket is filed.
""".trimIndent()

internal val BIG_HANDBOOK = SMALL_HANDBOOK.copy(
    text = SMALL_HANDBOOK.text + "\n\n" + NOISE_SECTIONS,
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

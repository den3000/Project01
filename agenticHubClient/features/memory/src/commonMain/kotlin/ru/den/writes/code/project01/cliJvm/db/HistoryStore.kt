package ru.den.writes.code.project01.cliJvm.db

import ru.den.writes.code.project01.cliJvm.SessionStats
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Usage

/**
 * Conversation-history persistence port for one session, with an in-memory
 * view so callers don't keep their own copy. Neutral surface — [Message] /
 * [Usage] / [SessionStats] / plain strings + the [SummarySnapshot] /
 * [FactsSnapshot] value types cross the portable boundary, so the
 * conversation runtime (`TurnEngine`, `ContextStrategy`, `StickyFacts`,
 * `CommandRunner`) depends only on this interface. The Room-backed
 * implementation ([RoomHistoryStore]) hides the DAO + entity types.
 *
 * Bound to one session and one [branchId] (switchable via [switchTo]); all
 * load/append/summary/facts operations are scoped to that (session, branch).
 */
public interface HistoryStore {
    /** Active conversation branch. Mutated only by [switchTo]. */
    val branchId: String

    /** Live view of currently-loaded messages (same backing list across calls). */
    val messages: List<Message>

    /** Cumulative tokens + USD cost for this session, incl. prior runs. */
    val stats: SessionStats

    /** Hydrate the view and seed [stats] from storage. Call once on startup. */
    suspend fun load()

    /**
     * Append one message. For ASSISTANT messages pass the matching [usage] +
     * [modelId] (stored + folded into [stats]); leave null for USER messages.
     */
    suspend fun append(message: Message, usage: Usage? = null, modelId: String? = null)

    /** Persist the rolling compression summary and fold [usage] into [stats] as overhead. */
    suspend fun saveSummary(summaryText: String, coveredCount: Int, modelId: String?, usage: Usage?)

    /** The persisted summary for this (session, branch), or null. */
    suspend fun loadSummary(): SummarySnapshot?

    /** Persist the sticky-facts blob and fold [usage] into [stats] as overhead. */
    suspend fun saveFacts(factsJson: String, modelId: String?, usage: Usage?)

    /** The persisted sticky-facts for this (session, branch), or null. */
    suspend fun loadFacts(): FactsSnapshot?

    /** Switch the active branch and re-hydrate from storage for it. */
    suspend fun switchTo(newBranch: String)

    /** All branch ids for this session, in order of first appearance. */
    suspend fun branches(): List<String>

    /** Fork the current branch into [newBranch] (messages + summary + facts). Does not switch. */
    suspend fun fork(newBranch: String)

    /** Delete [branch] (messages + summary + facts). Caller must not pass the active [branchId]. */
    suspend fun deleteBranch(branch: String)
}

/**
 * The persisted compression summary the runtime needs on resume: the
 * summary text + how many head messages it covers. Overhead token totals
 * are re-seeded into [HistoryStore.stats] on [HistoryStore.load], not here.
 */
public data class SummarySnapshot(val summaryText: String, val coveredCount: Int)

/** The persisted sticky-facts blob the runtime hydrates its extractor from. */
public data class FactsSnapshot(val factsJson: String)

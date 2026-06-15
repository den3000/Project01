package ru.den.writes.code.project01.cliJvm.db

import ru.den.writes.code.project01.cliJvm.Message
import ru.den.writes.code.project01.cliJvm.PricingRegistry
import ru.den.writes.code.project01.cliJvm.Role
import ru.den.writes.code.project01.cliJvm.SessionStats
import ru.den.writes.code.project01.cliJvm.Usage

/**
 * Conversation-history persistence for one session, with an in-memory
 * cache so callers don't have to keep their own copy.
 *
 * Thin wrapper over [MessageDao] that hides Room types from the rest
 * of the codebase. [Agent] depends only on this — it never sees
 * [MessageEntity] or DAO directly. Translation between the neutral
 * [Message] / [Role] (which cross the [ru.den.writes.code.project01.cliJvm.LlmApi]
 * boundary) and the persisted [MessageEntity] (string `role` column,
 * `session_id` discriminator) lives here.
 *
 * Token bookkeeping ([stats]) also lives here: on [load] the running
 * totals are seeded from existing ASSISTANT rows so a resumed session
 * picks up exactly where the last process left off; on [append] with a
 * non-null `usage` the totals tick up. The Day-9 compression summary is
 * persisted here too ([saveSummary] / [loadSummary]); its summarization
 * calls fold into [stats] as overhead (tokens + cost, but not turns) and
 * are re-seeded from the summary row on [load]. Cost is recomputed from
 * tokens + `modelId` via [PricingRegistry] each time — it isn't stored.
 *
 * The store is bound to one [sessionId] at construction and one [branchId]
 * (switchable via [switchTo]); all load/append/summary/facts operations are
 * scoped to that (session, branch) pair.
 */
internal class HistoryStore(
    private val dao: MessageDao,
    private val sessionId: String,
    initialBranch: String = DEFAULT_BRANCH,
) {
    /**
     * Active conversation branch (Day-10). Mutated only by [switchTo], which
     * re-hydrates the store from disk for the new branch.
     */
    var branchId: String = initialBranch
        private set

    /**
     * Backing cache so we don't hit SQLite on every turn just to rebuild
     * the message list. Kept in sync with the DB: [load] replaces it,
     * [append] grows it after a successful insert.
     */
    private val cache = mutableListOf<Message>()

    /**
     * Live view of currently-loaded messages. Returns the same backing
     * list across calls, so callers see new entries automatically after
     * [append]; defensive copies are the caller's responsibility if they
     * need a snapshot.
     */
    val messages: List<Message> get() = cache

    /**
     * Cumulative tokens + USD cost for this session, including any
     * prior runs reconstructed from the DB. Read-only from outside —
     * only [load] (seeding) and [append] (mid-session updates) mutate
     * it.
     */
    var stats: SessionStats = SessionStats()
        private set

    /**
     * Hydrate the cache and seed [stats] from the DB. Call once on
     * startup. Subsequent calls would refresh from disk, but in this
     * app's single-process model there's no scenario where the DB
     * diverges from the cache after init.
     */
    suspend fun load() {
        // Reset first so load() is safe to re-run (e.g. on a branch switch):
        // a fresh SessionStats avoids double-counting the previous branch.
        cache.clear()
        stats = SessionStats()

        val loaded = dao.all(sessionId, branchId).map {
            Message(role = Role.valueOf(it.role), text = it.text)
        }
        cache += loaded
        stats.seedFrom(dao.assistantMessages(sessionId, branchId), PricingRegistry::lookup)
        // Re-seed compaction overhead from the persisted summary row (if
        // any) so a resumed compressed branch's footer stays honest about
        // summarization spend. The compressor's own state (summary text +
        // watermark) is hydrated separately by the caller via loadSummary().
        dao.getSummary(sessionId, branchId)?.let { row ->
            row.overheadUsageOrNull()?.let { usage ->
                val cost = row.modelId?.let(PricingRegistry::lookup)
                    ?.let { PricingRegistry.cost(usage, it) }
                    ?: 0.0
                stats.recordOverhead(usage, cost)
            }
        }
        // Likewise re-seed sticky-facts extraction overhead (Day-10).
        dao.getFacts(sessionId, branchId)?.let { row ->
            row.overheadUsageOrNull()?.let { usage ->
                val cost = row.modelId?.let(PricingRegistry::lookup)
                    ?.let { PricingRegistry.cost(usage, it) }
                    ?: 0.0
                stats.recordOverhead(usage, cost)
            }
        }
    }

    /**
     * Append one message to this session's history. Persists to DB first,
     * then mirrors into the cache — that way a failed insert (throws)
     * doesn't leave the in-memory view ahead of the DB.
     *
     * For ASSISTANT messages, pass the matching [usage] and [modelId]
     * from the API response: they're stored on the row and folded into
     * [stats]. For USER messages, leave them null (default) — token
     * counts describe the API call, not the user input.
     *
     * Used twice per agent turn (user message + model reply) and only
     * after a successful exchange, so a crashed turn never leaves a
     * half-conversation on disk.
     */
    suspend fun append(
        message: Message,
        usage: Usage? = null,
        modelId: String? = null,
    ) {
        dao.insert(
            MessageEntity(
                sessionId = sessionId,
                branchId = branchId,
                role = message.role.name,
                text = message.text,
                modelId = modelId,
                promptTokens = usage?.promptTokens,
                outputTokens = usage?.outputTokens,
                thoughtsTokens = usage?.thoughtsTokens,
                totalTokens = usage?.totalTokens,
            )
        )
        cache += message
        if (usage != null) {
            val cost = modelId?.let(PricingRegistry::lookup)
                ?.let { PricingRegistry.cost(usage, it) }
                ?: 0.0
            stats.record(usage, cost)
        }
    }

    /**
     * Persist the rolling compression summary for this session and fold
     * the summarization call's [usage] into [stats] as overhead (tokens +
     * cost, but not a turn — see [SessionStats.recordOverhead]).
     *
     * The token columns on the row are *cumulative* across every
     * compaction in the session: we read the prior row and add this call's
     * usage, so a later [load] re-seeds the full overhead in one shot. Only
     * this call's usage is folded into [stats] live, because earlier
     * compactions of the current run already recorded theirs (and prior
     * runs are recovered via [load]). Cost is recomputed from tokens +
     * [modelId] via [PricingRegistry], never stored.
     */
    suspend fun saveSummary(
        summaryText: String,
        coveredCount: Int,
        modelId: String?,
        usage: Usage?,
    ) {
        val prior = dao.getSummary(sessionId, branchId)
        dao.upsertSummary(
            SummaryEntity(
                sessionId = sessionId,
                branchId = branchId,
                summaryText = summaryText,
                coveredCount = coveredCount,
                modelId = modelId ?: prior?.modelId,
                promptTokens = sumTokens(prior?.promptTokens, usage?.promptTokens),
                outputTokens = sumTokens(prior?.outputTokens, usage?.outputTokens),
                thoughtsTokens = sumTokens(prior?.thoughtsTokens, usage?.thoughtsTokens),
                totalTokens = sumTokens(prior?.totalTokens, usage?.totalTokens),
            )
        )
        if (usage != null) {
            val cost = modelId?.let(PricingRegistry::lookup)
                ?.let { PricingRegistry.cost(usage, it) }
                ?: 0.0
            stats.recordOverhead(usage, cost)
        }
    }

    /**
     * The persisted summary row for this session, or null if none stored.
     * The caller (Agent) uses it to hydrate the compressor's summary +
     * watermark on resume; the overhead totals were already re-seeded by
     * [load].
     */
    suspend fun loadSummary(): SummaryEntity? = dao.getSummary(sessionId, branchId)

    /**
     * Persist the sticky-facts blob for this (session, branch) and fold the
     * extraction call's [usage] into [stats] as overhead (tokens + cost, but
     * not a turn). Mirrors [saveSummary]: the token columns are cumulative
     * across the conversation, so a later [load] re-seeds the full extraction
     * overhead in one shot.
     */
    suspend fun saveFacts(factsJson: String, modelId: String?, usage: Usage?) {
        val prior = dao.getFacts(sessionId, branchId)
        dao.upsertFacts(
            FactsEntity(
                sessionId = sessionId,
                branchId = branchId,
                factsJson = factsJson,
                modelId = modelId ?: prior?.modelId,
                promptTokens = sumTokens(prior?.promptTokens, usage?.promptTokens),
                outputTokens = sumTokens(prior?.outputTokens, usage?.outputTokens),
                thoughtsTokens = sumTokens(prior?.thoughtsTokens, usage?.thoughtsTokens),
                totalTokens = sumTokens(prior?.totalTokens, usage?.totalTokens),
            )
        )
        if (usage != null) {
            val cost = modelId?.let(PricingRegistry::lookup)
                ?.let { PricingRegistry.cost(usage, it) }
                ?: 0.0
            stats.recordOverhead(usage, cost)
        }
    }

    /** The persisted sticky-facts row for this (session, branch), or null. */
    suspend fun loadFacts(): FactsEntity? = dao.getFacts(sessionId, branchId)

    /**
     * Switch the active branch and re-hydrate the store from disk for it.
     * [load] resets the cache + [stats] so nothing from the previous branch
     * leaks in. Strategy-side state (e.g. the compressor's summary) is
     * re-hydrated separately by the caller via the strategy's rebind hook.
     */
    suspend fun switchTo(newBranch: String) {
        branchId = newBranch
        load()
    }

    /** All branch ids that exist for this session, in order of first appearance. */
    suspend fun branches(): List<String> = dao.branchesOf(sessionId)

    /**
     * Fork the current branch into [newBranch]: copy this (session, branch)'s
     * messages (with token columns, so the fork's stats stay honest), plus its
     * summary and facts rows if present. Does not switch — the caller switches
     * explicitly via [switchTo].
     */
    suspend fun fork(newBranch: String) {
        dao.copyBranchMessages(sessionId = sessionId, fromBranch = branchId, toBranch = newBranch)
        dao.getSummary(sessionId, branchId)?.let { dao.upsertSummary(it.copy(branchId = newBranch)) }
        dao.getFacts(sessionId, branchId)?.let { dao.upsertFacts(it.copy(branchId = newBranch)) }
    }
}

/**
 * Sum two nullable token counts, preserving "no data": null + null → null,
 * otherwise a missing side counts as 0.
 */
private fun sumTokens(a: Int?, b: Int?): Int? =
    if (a == null && b == null) null else (a ?: 0) + (b ?: 0)

/**
 * Lift a [SummaryEntity]'s stored overhead token columns back into a
 * [Usage], or null if the row carries no token data — mirrors how
 * [SessionStats] reconstructs per-row usage from [MessageEntity].
 */
private fun SummaryEntity.overheadUsageOrNull(): Usage? {
    val p = promptTokens ?: return null
    val o = outputTokens ?: return null
    val t = totalTokens ?: return null
    return Usage(promptTokens = p, outputTokens = o, thoughtsTokens = thoughtsTokens ?: 0, totalTokens = t)
}

/**
 * Lift a [FactsEntity]'s stored overhead token columns back into a [Usage],
 * or null if the row carries no token data — mirrors
 * [SummaryEntity.overheadUsageOrNull].
 */
private fun FactsEntity.overheadUsageOrNull(): Usage? {
    val p = promptTokens ?: return null
    val o = outputTokens ?: return null
    val t = totalTokens ?: return null
    return Usage(promptTokens = p, outputTokens = o, thoughtsTokens = thoughtsTokens ?: 0, totalTokens = t)
}

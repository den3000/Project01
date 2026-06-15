package ru.den.writes.code.project01.cliJvm.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

/**
 * DAO over the `messages` table plus the per-session `summaries` table.
 *
 * Every read/write is scoped by `session_id`, except [listSessions]
 * which is the cross-session query used by the `-sessions` list-mode.
 *
 * All methods are `suspend`. Room generates main-thread-safe impls;
 * since `Agent.send()` is already `suspend`, the call sites plug in
 * with no extra ceremony.
 */
@Dao
internal interface MessageDao {
    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY id ASC")
    suspend fun all(sessionId: String): List<MessageEntity>

    /**
     * ASSISTANT-only rows for the given session, in conversation order.
     * Used by [HistoryStore] to seed lifetime token/cost totals on
     * resume — USER rows have all the token columns null, so filtering
     * them out at the SQL layer is cheaper than scanning everything.
     */
    @Query(
        "SELECT * FROM messages WHERE session_id = :sessionId AND role = 'ASSISTANT' ORDER BY id ASC"
    )
    suspend fun assistantMessages(sessionId: String): List<MessageEntity>

    /**
     * Last [n] rows of [sessionId] in chronological order. Powers the
     * `-inflate` CLI op: callers re-insert the returned rows verbatim
     * (modulo `id` and token columns) to fast-forward a session's
     * prompt-token size without making LLM calls.
     *
     * SQLite returns them in DESC order so we can apply LIMIT, then we
     * sort back to ASC client-side via the outer query — no need for a
     * subquery here, this is one disk read.
     */
    @Query(
        "SELECT * FROM messages WHERE session_id = :sessionId " +
            "AND id IN (SELECT id FROM messages WHERE session_id = :sessionId ORDER BY id DESC LIMIT :n) " +
            "ORDER BY id ASC"
    )
    suspend fun tail(sessionId: String, n: Int): List<MessageEntity>

    @Insert
    suspend fun insert(entity: MessageEntity)

    /**
     * Cross-session summary for the `-sessions` list-mode: one row per
     * known session id with how many messages it has, ordered by when
     * the session first appeared (MIN(id) as a cheap "created at"
     * proxy).
     */
    @Query(
        "SELECT session_id AS sessionId, COUNT(*) AS count " +
            "FROM messages GROUP BY session_id ORDER BY MIN(id)"
    )
    suspend fun listSessions(): List<SessionSummary>

    /** Total row count across every session — used by `-clean` to report how many were wiped. */
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    /**
     * Delete every row from the messages table. Used by `-clean`. The
     * schema (table + index + sqlite_sequence) is left intact, so the
     * next write reuses the same DB without going through Room
     * re-initialisation.
     */
    @Query("DELETE FROM messages")
    suspend fun clearAll()

    // --- summaries (history compression, schema v3) -----------------

    /** The rolling-summary row for a session, or null if none stored yet. */
    @Query("SELECT * FROM summaries WHERE session_id = :sessionId")
    suspend fun getSummary(sessionId: String): SummaryEntity?

    /**
     * Insert-or-replace the rolling summary for a session. One row per
     * session (session_id is the PK), so this keeps exactly one current
     * summary as it's rebuilt across compactions.
     */
    @Upsert
    suspend fun upsertSummary(entity: SummaryEntity)

    /**
     * Delete every summary row. Paired with [clearAll] under `-clean` so a
     * wiped DB doesn't leave an orphan summary that would resurrect on a
     * reused session id.
     */
    @Query("DELETE FROM summaries")
    suspend fun clearAllSummaries()

    // --- facts (sticky-facts strategy, schema v4) -------------------

    /** The sticky-facts row for a (session, branch), or null if none stored yet. */
    @Query("SELECT * FROM facts WHERE session_id = :sessionId AND branch_id = :branchId")
    suspend fun getFacts(sessionId: String, branchId: String): FactsEntity?

    /** Insert-or-replace the facts blob for a (session, branch) — one row each. */
    @Upsert
    suspend fun upsertFacts(entity: FactsEntity)

    /** Delete every facts row. Paired with [clearAll] under `-clean`. */
    @Query("DELETE FROM facts")
    suspend fun clearAllFacts()
}

/** Row shape returned by [MessageDao.listSessions]. */
internal data class SessionSummary(
    val sessionId: String,
    val count: Int,
)

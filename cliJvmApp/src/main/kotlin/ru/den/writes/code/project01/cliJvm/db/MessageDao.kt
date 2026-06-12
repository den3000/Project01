package ru.den.writes.code.project01.cliJvm.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * DAO over the `messages` table.
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
}

/** Row shape returned by [MessageDao.listSessions]. */
internal data class SessionSummary(
    val sessionId: String,
    val count: Int,
)

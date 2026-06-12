package ru.den.writes.code.project01.cliJvm.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One persisted message row.
 *
 * - [id] — auto-incremented primary key, gives the natural ordering of
 *   the conversation when we `ORDER BY id ASC`.
 * - [sessionId] — discriminator for the conversation this message
 *   belongs to. All DAO queries scope by this column; that's how
 *   parallel runs with different `-session` flags stay isolated in the
 *   same physical DB file.
 * - [role] — `"USER"` or `"ASSISTANT"`, stored as a String. The
 *   mapping to/from the neutral [ru.den.writes.code.project01.cliJvm.Role]
 *   enum lives in [HistoryStore]; Room-side it's just a string column
 *   so the schema stays stable even if we add new role values later.
 * - [text] — the message body.
 *
 * Index on `session_id` keeps lookups O(log n) once the table grows
 * past a few thousand rows across many sessions.
 */
@Entity(
    tableName = "messages",
    indices = [Index("session_id")],
)
internal data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,
    val text: String,
)

package ru.den.writes.code.project01.cliJvm.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Default branch every session lives on until the user forks one. */
internal const val DEFAULT_BRANCH: String = "main"

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
 * - [modelId] / [promptTokens] / [outputTokens] / [thoughtsTokens] /
 *   [totalTokens] — populated **only for ASSISTANT rows**, describing
 *   the API call that produced the reply. USER rows leave them all
 *   null because USER messages don't trigger an API call by themselves;
 *   they describe input, not consumption. Stored together with the
 *   reply so `-sessions` and the lifetime-totals seed for [HistoryStore]
 *   can be computed without re-asking the provider. Cost is **not**
 *   stored — it's recomputed from these counts + [modelId] via
 *   [ru.den.writes.code.project01.cliJvm.PricingRegistry], so when rates
 *   change we re-price old sessions for free.
 *
 * Index on `(session_id, branch_id)` keeps lookups O(log n) once the table
 * grows past a few thousand rows across many sessions / branches.
 */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["session_id", "branch_id"])],
)
internal data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,
    val text: String,
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Int? = null,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int? = null,
    @ColumnInfo(name = "thoughts_tokens") val thoughtsTokens: Int? = null,
    @ColumnInfo(name = "total_tokens") val totalTokens: Int? = null,
    /**
     * Conversation branch this row belongs to. Added in
     * schema v4; pre-v4 rows are backfilled to [DEFAULT_BRANCH]. Together
     * with [sessionId] it scopes every per-conversation query.
     */
    @ColumnInfo(name = "branch_id") val branchId: String = DEFAULT_BRANCH,
)

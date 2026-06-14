package ru.den.writes.code.project01.cliJvm.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted rolling-summary state for one session (Day-9 history
 * compression). One row per session — `session_id` is the primary key.
 *
 * Holds the current rolling [summaryText] and the [coveredCount]
 * watermark (how many leading messages of the session the summary
 * represents). The token columns + [modelId] record the *cumulative*
 * cost of building this summary across all compactions in the session, so
 * a resumed session can re-seed its overhead totals. Cost itself is not
 * stored — it's recomputed from tokens + model via `PricingRegistry`,
 * mirroring how [MessageEntity] handles per-turn cost.
 *
 * Token columns are nullable: a row may exist before any usage was
 * reported, and sessions that never compressed simply have no row at all.
 *
 * Lives in its own `summaries` table (added in schema v3) and is never
 * mixed into the append-only `messages` log — that log stays the full
 * verbatim history, so running a session without `-compress` replays
 * everything unchanged.
 */
@Entity(tableName = "summaries")
internal data class SummaryEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "summary_text") val summaryText: String,
    @ColumnInfo(name = "covered_count") val coveredCount: Int,
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Int? = null,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int? = null,
    @ColumnInfo(name = "thoughts_tokens") val thoughtsTokens: Int? = null,
    @ColumnInfo(name = "total_tokens") val totalTokens: Int? = null,
)

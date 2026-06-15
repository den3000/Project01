package ru.den.writes.code.project01.cliJvm.db

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Persisted "sticky facts" — a rolling key-value memory of the conversation
 * (Day-10 Sticky Facts strategy). One row per (session, branch); the
 * composite primary key mirrors [SummaryEntity].
 *
 * [factsJson] is a JSON object string (arbitrary user-derived keys). The
 * token columns + [modelId] record the *cumulative* cost of the
 * fact-extraction calls across the conversation, so a resumed session can
 * re-seed its overhead totals — same shape and rationale as [SummaryEntity].
 * Cost is recomputed from tokens + model, never stored.
 */
@Entity(tableName = "facts", primaryKeys = ["session_id", "branch_id"])
internal data class FactsEntity(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "branch_id") val branchId: String = DEFAULT_BRANCH,
    @ColumnInfo(name = "facts_json") val factsJson: String,
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Int? = null,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int? = null,
    @ColumnInfo(name = "thoughts_tokens") val thoughtsTokens: Int? = null,
    @ColumnInfo(name = "total_tokens") val totalTokens: Int? = null,
)

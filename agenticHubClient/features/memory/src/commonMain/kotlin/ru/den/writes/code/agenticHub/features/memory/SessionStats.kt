package ru.den.writes.code.agenticHub.features.memory

import ru.den.writes.code.agenticHub.features.llm.Usage

/**
 * In-memory running totals for one session.
 *
 * Owned by [ru.den.writes.code.agenticHub.features.memory.db.HistoryStore] — it
 * seeds the counters from persisted rows on `load()` (see the
 * `SessionStats.seedFrom` extension in the `db` layer) and increments
 * them on every successful `append(... usage)`. Agent never mutates a
 * [SessionStats] directly; it only reads to print the footer.
 *
 * Cost is held as a Double accumulator. Per-row cost is recomputed
 * from tokens × pricing on the way in by the caller, so the running
 * total always reflects the current pricing — old rows re-price for
 * free when rates change.
 */
public class SessionStats {
    var totalPromptTokens: Int = 0
        private set
    var totalOutputTokens: Int = 0
        private set
    var totalThoughtsTokens: Int = 0
        private set
    var totalCostUsd: Double = 0.0
        private set
    var turns: Int = 0
        private set

    /** Total tokens across all turns. Sum of prompt + output + thoughts. */
    val totalTokens: Int
        get() = totalPromptTokens + totalOutputTokens + totalThoughtsTokens

    /** Fold one turn's worth of usage + cost into the running totals. */
    fun record(usage: Usage, costUsd: Double) {
        totalPromptTokens += usage.promptTokens
        totalOutputTokens += usage.outputTokens
        totalThoughtsTokens += usage.thoughtsTokens
        totalCostUsd += costUsd
        turns += 1
    }

    /**
     * Fold token usage + cost from a *non-turn* LLM call into the running
     * totals WITHOUT counting it as a turn.
     *
     * Used for history-compaction summarization calls: they spend real
     * tokens (so prompt/output/cost must stay honest), but they are not
     * user/assistant exchanges. [turns] — which the footer's `turns=N`
     * and the `-sessions` view rely on — must keep meaning "real
     * exchanges only", so it is deliberately left untouched here.
     */
    fun recordOverhead(usage: Usage, costUsd: Double) {
        totalPromptTokens += usage.promptTokens
        totalOutputTokens += usage.outputTokens
        totalThoughtsTokens += usage.thoughtsTokens
        totalCostUsd += costUsd
    }
}

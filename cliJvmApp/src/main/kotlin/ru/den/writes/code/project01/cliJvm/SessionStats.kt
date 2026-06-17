package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.cliJvm.db.MessageEntity
import ru.den.writes.code.project01.shared.llm.Usage
import ru.den.writes.code.project01.shared.pricing.ModelPricing
import ru.den.writes.code.project01.shared.pricing.PricingRegistry

/**
 * In-memory running totals for one session.
 *
 * Owned by [ru.den.writes.code.project01.cliJvm.db.HistoryStore] — it
 * seeds the counters from the DB on `load()` and increments them on
 * every successful `append(... usage)`. Agent never mutates a
 * [SessionStats] directly; it only reads to print the footer.
 *
 * Cost is held as a Double accumulator. Per-row cost is recomputed
 * from tokens × pricing on the way in, so the running total always
 * reflects the current [PricingRegistry] — old rows re-price for free
 * when rates change.
 */
internal class SessionStats {
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

    /**
     * Replay accumulated rows into the counters. Used to restore the
     * running totals when resuming an existing session from disk.
     *
     * Rows without a recorded [Usage] (USER turns, or rows stored
     * under the v1 schema) are silently skipped — they don't
     * have token data to attribute. Rows with tokens but unknown
     * `model_id` (or a `model_id` not in [PricingRegistry]) are still
     * counted toward token totals but contribute zero cost; that's
     * "best honest answer" — we don't fabricate a rate.
     */
    fun seedFrom(rows: List<MessageEntity>, pricing: (String) -> ModelPricing?) {
        rows.forEach { row ->
            val usage = row.toUsageOrNull() ?: return@forEach
            val cost = row.modelId?.let(pricing)?.let { PricingRegistry.cost(usage, it) } ?: 0.0
            record(usage, cost)
        }
    }
}

/**
 * Lift a stored [MessageEntity] back into a neutral [Usage], or `null`
 * if any of the four counter columns is missing — that's what
 * v1-schema rows and USER turns look like on disk.
 */
private fun MessageEntity.toUsageOrNull(): Usage? {
    val prompt = promptTokens ?: return null
    val output = outputTokens ?: return null
    val total = totalTokens ?: return null
    return Usage(
        promptTokens = prompt,
        outputTokens = output,
        thoughtsTokens = thoughtsTokens ?: 0,
        totalTokens = total,
    )
}

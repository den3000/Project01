package ru.den.writes.code.project01.cliJvm.db

import ru.den.writes.code.agenticHub.platform.database.MessageEntity
import ru.den.writes.code.project01.cliJvm.SessionStats
import ru.den.writes.code.agenticHub.features.llm.Usage
import ru.den.writes.code.agenticHub.features.llm.pricing.ModelPricing
import ru.den.writes.code.agenticHub.features.llm.pricing.PricingRegistry

/**
 * Replay accumulated [MessageEntity] rows into a [SessionStats]. Used to
 * restore the running totals when resuming an existing session from disk.
 *
 * Lives in the `db` layer (not on [SessionStats] itself) so the counter is
 * a neutral, storage-agnostic type — only this extension knows about the
 * Room [MessageEntity] row shape.
 *
 * Rows without a recorded [Usage] (USER turns, or rows stored under the v1
 * schema) are silently skipped — they don't have token data to attribute.
 * Rows with tokens but unknown `model_id` (or a `model_id` not in
 * [PricingRegistry]) are still counted toward token totals but contribute
 * zero cost; that's "best honest answer" — we don't fabricate a rate.
 */
public fun SessionStats.seedFrom(rows: List<MessageEntity>, pricing: (String) -> ModelPricing?) {
    rows.forEach { row ->
        val usage = row.toUsageOrNull() ?: return@forEach
        val cost = row.modelId?.let(pricing)?.let { PricingRegistry.cost(usage, it) } ?: 0.0
        record(usage, cost)
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

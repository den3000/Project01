package ru.den.writes.code.project01.cliJvm.plain

import ru.den.writes.code.project01.cliJvm.SessionStatsSnapshot
import ru.den.writes.code.project01.cliJvm.formatContextFill
import ru.den.writes.code.project01.cliJvm.formatCost
import ru.den.writes.code.project01.cliJvm.formatSessionTokens
import ru.den.writes.code.project01.cliJvm.formatTurnTokens
import ru.den.writes.code.project01.shared.llm.Usage
import ru.den.writes.code.project01.shared.pricing.PricingRegistry

/** The 72-char `<<<…` footer rule. */
private const val FOOTER_RULE_WIDTH = 72

/** Threshold (% of context window) above which the footer warns about overflow. */
private const val CONTEXT_WARN_PCT: Double = 90.0

/**
 * The per-turn footer on stdout (rule / duration / turn / context-fill /
 * session / rule) plus, on stderr, a near-the-limit context-window warning
 * (so it survives a stdout redirect).
 */
internal data class TurnPlainView(
    val usage: Usage?,
    val modelId: String,
    val durationMs: Long,
    val session: SessionStatsSnapshot?,
) : PlainView {
    private val pricing = PricingRegistry.lookup(modelId)

    override fun stdout(): List<String> = buildList {
        val rule = "<".repeat(FOOTER_RULE_WIDTH)
        add(rule)
        add("duration: $durationMs ms")
        if (usage != null) {
            val cost = pricing?.let { PricingRegistry.cost(usage, it) }
            add("turn:    " + formatTurnTokens(usage) + "  cost=${formatCost(cost, pricing != null)}")
            pricing?.contextWindowTokens?.let { window ->
                add(formatContextFill(usage.promptTokens, window))
            }
            session?.let { s ->
                val sessionCost = if (pricing != null) s.costUsd else null
                add(
                    "session: turns=${s.turns} " + formatSessionTokens(s) +
                        "  cost=${formatCost(sessionCost, pricing != null)}"
                )
            }
        }
        add(rule)
    }

    override fun stderr(): List<String> = buildList {
        val window = pricing?.contextWindowTokens
        if (usage != null && window != null) {
            val pct = usage.promptTokens.toDouble() / window * 100.0
            if (pct >= CONTEXT_WARN_PCT) {
                add("[warning] context window %.1f%% full — next turn may overflow".format(pct))
            }
        }
    }
}

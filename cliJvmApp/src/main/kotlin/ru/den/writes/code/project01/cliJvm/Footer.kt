package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.shared.invariant.InvariantVerdict
import ru.den.writes.code.project01.shared.llm.Usage

/**
 * Footer / agent-tag formatting shared by [PlainView] (and the TUI's stats
 * panel). Pure string builders — the renderers decide where the lines go.
 */

/**
 * Display tag naming the agent that produced a reply, e.g.
 * `[[AGENT: interviewer:gemini-2.5-flash]]`. Emitted only in multi-agent
 * sessions; a null profile shows as `default`.
 */
internal fun agentTag(profileName: String?, modelId: String): String =
    "[[AGENT: ${profileName ?: "default"}:$modelId]]"

/**
 * The breach lines an invariant [verdict] produces, in render order: one
 * `[invariant] violated …` per violation, then the `reply not saved …`
 * trailer. Shared by [PlainView] (stderr) and the TUI `judge` column so the
 * two never drift. Empty when the verdict passed.
 */
internal fun invariantLines(verdict: InvariantVerdict): List<String> =
    if (verdict.passed) {
        emptyList()
    } else {
        verdict.violations.map {
            "[invariant] violated ${it.ruleId ?: "constraint"}: ${it.explanation}"
        } + "[invariant] reply not saved to history; task stage held"
    }

/**
 * Render the post-turn context-window fill line. Caller invokes this only when
 * the model's context window is known.
 *
 * Example: `(120000, 1000000) → "context: 120000 / 1000000 (12.0%)"`.
 */
internal fun formatContextFill(promptTokens: Int, windowTokens: Int): String {
    val pct = promptTokens.toDouble() / windowTokens * 100.0
    return "context: %d / %d (%.1f%%)".format(promptTokens, windowTokens, pct)
}

internal fun formatTurnTokens(usage: Usage): String = buildString {
    append("prompt=${usage.promptTokens}  output=${usage.outputTokens}")
    if (usage.thoughtsTokens > 0) append("  thoughts=${usage.thoughtsTokens}")
    append("  total=${usage.totalTokens}")
}

internal fun formatSessionTokens(stats: SessionStatsSnapshot): String = buildString {
    append("prompt=${stats.promptTokens}  output=${stats.outputTokens}")
    if (stats.thoughtsTokens > 0) append("  thoughts=${stats.thoughtsTokens}")
    append("  total=${stats.totalTokens}")
}

internal fun formatCost(usd: Double?, knownPricing: Boolean): String =
    when {
        !knownPricing -> "$? (no pricing)"
        usd == null -> "$? (no pricing)"
        else -> "$%.5f".format(usd)
    }

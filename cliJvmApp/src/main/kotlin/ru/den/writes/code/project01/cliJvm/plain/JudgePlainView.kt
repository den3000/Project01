package ru.den.writes.code.project01.cliJvm.plain

import ru.den.writes.code.project01.shared.invariant.InvariantViolation

/**
 * Invariant-judge breach lines on stderr — one `[invariant] violated …` per
 * violation, then the `reply not saved …` trailer. PlainView shows no
 * judge-model tag (parity with the prior output) — that's a TUI-only adornment.
 */
internal data class JudgePlainView(val violations: List<InvariantViolation>) : PlainView {
    override fun stderr(): List<String> =
        if (violations.isEmpty()) {
            emptyList()
        } else {
            violations.map { "[invariant] violated ${it.ruleId ?: "constraint"}: ${it.explanation}" } +
                "[invariant] reply not saved to history; task stage held"
        }
}

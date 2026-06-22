package ru.den.writes.code.project01.cliJvm.plain

import ru.den.writes.code.project01.cliJvm.invariantLines
import ru.den.writes.code.project01.shared.invariant.InvariantViolation

/**
 * Invariant-judge breach lines on stderr. PlainView shows no judge-model tag
 * (parity with the prior output) — the model id is a TUI-only adornment.
 */
internal data class JudgePlainView(val violations: List<InvariantViolation>) : PlainView {
    override fun stderr(): List<String> = invariantLines(violations)
}

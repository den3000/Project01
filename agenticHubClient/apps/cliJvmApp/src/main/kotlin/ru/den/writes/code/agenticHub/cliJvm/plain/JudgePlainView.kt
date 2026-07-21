package ru.den.writes.code.agenticHub.cliJvm.plain

import ru.den.writes.code.agenticHub.features.lifecycle.session.judgeLines
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.JudgeOutcome

/**
 * Invariant-judge lines on stderr — one `[invariant] violated …` per objection,
 * then a trailer naming what it cost the turn. The wording lives in `judgeLines`,
 * shared with the TUI. PlainView shows no judge-model tag (parity with the prior
 * output) — that's a TUI-only adornment.
 */
internal data class JudgePlainView(val outcome: JudgeOutcome) : PlainView {
    override fun stderr(): List<String> = judgeLines(outcome)
}

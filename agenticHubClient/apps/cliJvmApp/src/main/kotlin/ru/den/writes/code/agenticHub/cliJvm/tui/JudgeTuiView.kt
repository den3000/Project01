package ru.den.writes.code.agenticHub.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.text.magenta
import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope
import ru.den.writes.code.agenticHub.features.lifecycle.session.judgeLines
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.JudgeOutcome

/**
 * An invariant-judge finding as a `"judge │ …"` column: the judge's `[[AGENT:]]`
 * tag on the first line (magenta), a blank spacer, then the finding (red). The
 * wording — including what the finding cost the turn — comes from `judgeLines`,
 * shared with PlainView, so the two can't drift.
 */
internal data class JudgeTuiView(
    val judgeModelId: String?,
    val outcome: JudgeOutcome,
) : TuiView {
    override fun RenderScope.render(terminal: Terminal, width: Int) {
        val breaches = judgeLines(outcome)
        if (breaches.isEmpty()) return
        val tag = "[[AGENT: ${judgeModelId ?: "?"}]]"
        val tagRows = wrapWords("judge", tag, width).size
        wrapWords("judge", "$tag\n\n" + breaches.joinToString("\n"), width).forEachIndexed { i, l ->
            if (i < tagRows) magenta { textLine(l) } else red { textLine(l) }
        }
    }
}

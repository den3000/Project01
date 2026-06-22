package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.text.magenta
import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope
import ru.den.writes.code.project01.shared.invariant.InvariantViolation

/**
 * An invariant-judge breach as a `"judge │ …"` column: the judge's `[[AGENT:]]`
 * tag on the first line (magenta), a blank spacer, then the breach lines (red).
 */
internal data class JudgeTuiView(
    val judgeModelId: String?,
    val violations: List<InvariantViolation>,
) : TuiView {
    override fun RenderScope.render(terminal: Terminal, width: Int) {
        if (violations.isEmpty()) return
        val breaches = violations.map { "[invariant] violated ${it.ruleId ?: "constraint"}: ${it.explanation}" } +
            "[invariant] reply not saved to history; task stage held"
        val tag = "[[AGENT: ${judgeModelId ?: "?"}]]"
        val tagRows = wrapWords("judge", tag, width).size
        wrapWords("judge", "$tag\n\n" + breaches.joinToString("\n"), width).forEachIndexed { i, l ->
            if (i < tagRows) magenta { textLine(l) } else red { textLine(l) }
        }
    }
}

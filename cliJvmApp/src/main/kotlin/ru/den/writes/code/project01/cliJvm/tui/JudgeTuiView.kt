package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.text.magenta
import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope
import ru.den.writes.code.project01.cliJvm.invariantLines
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
        val lines = invariantLines(violations)
        if (lines.isEmpty()) return
        val tag = "[[AGENT: ${judgeModelId ?: "?"}]]"
        val tagRows = wrapWords("judge", tag, width).size
        wrapWords("judge", "$tag\n\n" + lines.joinToString("\n"), width).forEachIndexed { i, l ->
            if (i < tagRows) magenta { textLine(l) } else red { textLine(l) }
        }
    }
}

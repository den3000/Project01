package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Panel
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.render.RenderScope
import ru.den.writes.code.agenticHub.features.viewmodel.SessionStatsSnapshot

/**
 * The live session-totals panel pinned in the bottom block (not a transcript
 * line): a Mordant `Panel` rendered to a plain box-drawing string, coloured
 * yellow by Kotter. The panel is rendered at a FIXED [width] with `expand` so it
 * doesn't resize as the numbers grow — a changing section width makes Kotter leak
 * the old (narrower) top border into the scrollback instead of repainting in place.
 */
internal data class SessionPanelTuiView(val stats: SessionStatsSnapshot) : TuiView {
    override fun RenderScope.render(terminal: Terminal, width: Int) {
        yellow { panelLines(width).forEach { textLine(it) } }
    }

    /** Box-drawing lines at a FIXED [width] (`expand`) — width is stable regardless of the numbers. */
    fun panelLines(width: Int): List<String> {
        val tokens = buildString {
            append("prompt=${stats.promptTokens}  output=${stats.outputTokens}")
            if (stats.thoughtsTokens > 0) append("  thoughts=${stats.thoughtsTokens}")
            append("  total=${stats.totalTokens}")
        }
        val costStr = "$%.5f".format(stats.costUsd)
        val fixed = Terminal(ansiLevel = AnsiLevel.NONE, width = width)
        return fixed.render(
            Panel(content = "turns=${stats.turns}  $tokens  cost=$costStr", title = "session", expand = true),
        ).trimEnd().lineSequence().toList()
    }
}

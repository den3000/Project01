package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Panel
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.render.RenderScope
import ru.den.writes.code.project01.cliJvm.SessionStatsSnapshot
import ru.den.writes.code.project01.cliJvm.formatSessionTokens

/**
 * The live session-totals panel pinned in the bottom block (not a transcript
 * line): a Mordant `Panel` rendered to a plain box-drawing string, coloured
 * yellow by Kotter. Width comes from the Mordant terminal, not [width].
 */
internal data class SessionPanelTuiView(val stats: SessionStatsSnapshot) : TuiView {
    override fun RenderScope.render(terminal: Terminal, width: Int) {
        val content = "turns=${stats.turns}  " + formatSessionTokens(stats) + "  cost=$%.5f".format(stats.costUsd)
        val panel = terminal.render(Panel(content = content, title = "session"))
        yellow { panel.trimEnd().lineSequence().forEach { textLine(it) } }
    }
}

package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.render.RenderScope

/** A pre-formatted status line (resume / command result / summary) — yellow. */
internal data class NoticeTuiView(val text: String) : TuiView {
    override fun RenderScope.render(terminal: Terminal, width: Int) {
        yellow { textLine(text) }
    }
}

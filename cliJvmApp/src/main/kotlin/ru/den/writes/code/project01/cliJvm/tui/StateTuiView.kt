package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.render.RenderScope

/**
 * A session-state line as a `"state │ …"` column (yellow): the resume banner
 * now, profile / task-state changes later. The text keeps its own `[tag]`.
 */
internal data class StateTuiView(val text: String) : TuiView {
    override fun RenderScope.render(terminal: Terminal, width: Int) {
        wrapWords("state", text, width).forEach { yellow { textLine(it) } }
    }
}

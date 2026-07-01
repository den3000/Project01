package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.text.cyan
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope

/** The user's echoed prompt — a cyan `"you │ …"` column. */
internal data class UserTuiView(val text: String) : TuiView {
    override fun RenderScope.render(terminal: Terminal, width: Int) {
        wrapWords("you", text, width).forEach { cyan { textLine(it) } }
    }
}

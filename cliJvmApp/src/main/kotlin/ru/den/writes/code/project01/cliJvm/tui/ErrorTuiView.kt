package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope

/** A failed turn as a red `"error │ …"` column; a multi-line reason wraps under the bar. */
internal data class ErrorTuiView(val reason: String) : TuiView {
    override fun RenderScope.render(terminal: Terminal, width: Int) {
        wrapWords("error", reason, width).forEach { red { textLine(it) } }
    }
}

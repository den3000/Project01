package ru.den.writes.code.project01.cliTui.tuiViews

import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.text.magenta
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope

data class ProfileChangedTuiView(val text: String) : TuiView {
    override fun RenderScope.render(terminal: Terminal) {
        magenta { textLine("you've selected: $text") }
    }
}
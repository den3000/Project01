package ru.den.writes.code.project01.cliTui.tuiViews

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Panel
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.render.RenderScope

data class BtmPanelTuiView(val title: String, val content: String) : TuiView {
    override fun RenderScope.render(terminal: Terminal) {
        val panel = terminal.render(Panel(content = content, title = title))
        yellow { panel.trimEnd().lineSequence().forEach { textLine(it) } }
    }
}
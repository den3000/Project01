package ru.den.writes.code.project01.cliTui.tuiViews

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Panel
import com.varabyte.kotter.foundation.text.magenta
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope

data class ProfilePickerTuiView(
    val options: List<String>,
    val cursor: Int,
) : TuiView {
    override fun RenderScope.render(terminal: Terminal) {
        val body = options.mapIndexed { i, opt ->
            val pointer = if (i == cursor) "▶" else " "
            val mark = if (opt == options[cursor]) "  ← активный" else ""
            "$pointer ${i + 1}. $opt$mark"
        }.joinToString("\n")
        val box = terminal.render(Panel(content = body, title = "профили  ↑↓ · Enter · Esc"))
        magenta { box.trimEnd().lineSequence().forEach { textLine(it) } }
    }
}
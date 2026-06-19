package ru.den.writes.code.project01.cliTui

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Panel
import com.varabyte.kotter.foundation.text.green
import com.varabyte.kotter.foundation.text.magenta
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.render.RenderScope

/**
 * Словарь рендера TUI: каждый вариант знает, как нарисовать себя в Kotter
 * [RenderScope]. Mordant-виджеты (Panel) рендерятся в строку с `AnsiLevel.NONE`
 * (чистый box-drawing), цвет накладывает Kotter снаружи. Чистая презентация —
 * без состояния и логики выбора (та живёт в [ChatViewModel]).
 */
sealed interface KotterView {
    fun RenderScope.render()

    fun renderIn(renderScope: RenderScope) = with(renderScope) { render() }

    data class SimpleListLine(val text: String) : KotterView {
        override fun RenderScope.render() {
            green { textLine("you've entered: $text") }
        }
    }

    data class ProfileChangedLine(val text: String) : KotterView {
        override fun RenderScope.render() {
            magenta { textLine("you've selected: $text") }
        }
    }

    data class BtmPanel(val terminal: Terminal, val title: String, val content: String) : KotterView {
        override fun RenderScope.render() {
            val panel = terminal.render(Panel(content = content, title = title))
            yellow { panel.trimEnd().lineSequence().forEach { textLine(it) } }
        }
    }

    data class ProfilePicker(
        val terminal: Terminal,
        val options: List<String>,
        val cursor: Int,
    ) : KotterView {
        override fun RenderScope.render() {
            val body = options.mapIndexed { i, opt ->
                val pointer = if (i == cursor) "▶" else " "
                val mark = if (opt == options[cursor]) "  ← активный" else ""
                "$pointer ${i + 1}. $opt$mark"
            }.joinToString("\n")
            val box = terminal.render(Panel(content = body, title = "профили  ↑↓ · Enter · Esc"))
            magenta { box.trimEnd().lineSequence().forEach { textLine(it) } }
        }
    }
}

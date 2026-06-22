package ru.den.writes.code.project01.cliTui.tuiViews

import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.runtime.render.RenderScope

/**
 * Словарь рендера TUI: каждый вариант знает, как нарисовать себя в Kotter
 * [com.varabyte.kotter.runtime.render.RenderScope]. Mordant-виджеты (Panel) рендерятся в строку с `AnsiLevel.NONE`
 * (чистый box-drawing), цвет накладывает Kotter снаружи. Чистая презентация —
 * без состояния и логики выбора (та живёт в [ru.den.writes.code.project01.cliTui.ChatViewModel]).
 */
sealed interface TuiView {
    fun RenderScope.render(terminal: Terminal)

    fun renderIn(renderScope: RenderScope, withTerminal: Terminal) = with(renderScope) { render(withTerminal) }

}
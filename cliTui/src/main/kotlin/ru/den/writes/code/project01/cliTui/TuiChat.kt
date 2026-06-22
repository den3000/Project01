package ru.den.writes.code.project01.cliTui

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.input
import com.varabyte.kotter.foundation.input.onInputEntered
import com.varabyte.kotter.foundation.input.onKeyPressed
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.runUntilSignal
import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.den.writes.code.project01.cliTui.tuiViews.BtmPanelTuiView
import ru.den.writes.code.project01.cliTui.tuiViews.ProfileChangedTuiView
import ru.den.writes.code.project01.cliTui.tuiViews.ProfilePickerTuiView
import ru.den.writes.code.project01.cliTui.tuiViews.SimpleListTuiView
import ru.den.writes.code.project01.cliTui.tuiViews.TuiView

/**
 * TUI-вид (Kotter+Mordant) поверх [ChatViewModel]: ввод → интент → VM обновляет
 * состояние → секция перерисовывается. Plain-собрат — [runPlainChat] поверх того
 * же VM. Единственный писатель состояния — VM, поэтому конкурентные коллекторы
 * Kotter (`onKeyPressed`/`onInputEntered`) не дерутся за общую var. Состояние
 * чистое ([ChatLine]/[PickerState]) — здесь оно мапится в [ru.den.writes.code.project01.cliTui.tuiViews.TuiView] для рендера.
 */
fun runTuiChat(vm: ChatViewModel) = session {
    val terminal = Terminal(ansiLevel = AnsiLevel.NONE, width = 60)
    var ui by liveVarOf(vm.state.value)
    val work = CoroutineScope(Dispatchers.Default)
    work.launch { vm.state.collect { ui = it } } // StateFlow → перерисовка Kotter

    section {
        ui.lines.forEach { it.toKotterView().renderIn(this, terminal) }
        text("> "); input()
        textLine()
        // picker/stats строим из чистого состояния — KotterView нужен Terminal.
        val bottom = ui.picker
            ?.let { ProfilePickerTuiView(it.options, it.cursor) }
            ?: BtmPanelTuiView(title = "stats", content = "number of inputs: ${ui.lines.size}")
        bottom.renderIn(this, terminal)
    }.runUntilSignal {
        work.launch { for (e in vm.effects) when (e) { ChatEffect.Exit -> signal() } }

        onKeyPressed {
            // Только навигация. Enter сюда НЕ маршрутизируем → нет гонки с onInputEntered.
            when (key) {
                Keys.Up -> vm.onIntent(ChatIntent.CursorUp)
                Keys.Down -> vm.onIntent(ChatIntent.CursorDown)
                Keys.Escape -> vm.onIntent(ChatIntent.Cancel)
            }
        }
        onInputEntered {
            val text = input.trim()
            clearInput()
            vm.onIntent(ChatIntent.Submit(text))
        }
    }
    work.cancel()
}

/** Чистая строка ленты → её Kotter-рендер. */
private fun ChatLine.toKotterView(): TuiView = when (this) {
    is ChatLine.Entered -> SimpleListTuiView(text)
    is ChatLine.Selected -> ProfileChangedTuiView(text)
}

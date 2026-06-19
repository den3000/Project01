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

/**
 * Kotter+Mordant вид поверх [ChatViewModel] (MVI): ввод → интент → VM обновляет
 * состояние → секция перерисовывается. Единственный писатель состояния — VM,
 * поэтому конкурентные коллекторы Kotter (`onKeyPressed`/`onInputEntered`) не
 * дерутся за общую var. Состояние чистое ([ChatLine]/[PickerState]) — здесь оно
 * мапится в [KotterView] для рендера.
 */
fun runMviChat(vm: ChatViewModel) = session {
    val widgets = Terminal(ansiLevel = AnsiLevel.NONE, width = 60)
    var ui by liveVarOf(vm.state.value)
    val work = CoroutineScope(Dispatchers.Default)
    work.launch { vm.state.collect { ui = it } } // StateFlow → перерисовка Kotter

    section {
        ui.lines.forEach { it.toKotterView().renderIn(this) }
        text("> "); input()
        textLine()
        // picker/stats строим из чистого состояния — KotterView нужен Terminal.
        val bottom = ui.picker
            ?.let { KotterView.ProfilePicker(widgets, it.options, it.cursor) }
            ?: KotterView.BtmPanel(widgets, title = "stats", content = "number of inputs: ${ui.lines.size}")
        bottom.renderIn(this)
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
private fun ChatLine.toKotterView(): KotterView = when (this) {
    is ChatLine.Entered -> KotterView.SimpleListLine(text)
    is ChatLine.Selected -> KotterView.ProfileChangedLine(text)
}

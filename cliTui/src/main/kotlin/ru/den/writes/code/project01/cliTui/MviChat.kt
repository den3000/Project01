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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Тот же экран, что в [runMyChat]/[runComboChat], но на однонаправленном цикле
 * (как MVI в Compose): ввод → интент → VM обновляет [ChatUiState] → секция
 * перерисовывается из состояния. Единственный писатель состояния — VM, поэтому
 * конкурентные коллекторы Kotter (`onKeyPressed`/`onInputEntered`) больше не
 * дерутся за общую `var`.
 */

/** Что view шлёт в VM. Семантика, а не сырые клавиши — VM не знает про Kotter. */
sealed interface ChatIntent {
    data class Submit(val text: String) : ChatIntent
    data object CursorUp : ChatIntent
    data object CursorDown : ChatIntent
    data object Cancel : ChatIntent
}

/** One-shot side effect: это не состояние (выход не «персистентен»). */
sealed interface ChatEffect {
    data object Exit : ChatEffect
}

/**
 * Состояние экрана.
 * [lines] — готовые к рендеру [KotterView] (пара чистых data-классов без
 * `Terminal`/лямбд). [picker] — чистые данные открытого списка; `null` → stats.
 */
data class ChatUiState(
    val lines: List<KotterView> = emptyList(),
    val picker: PickerState? = null,
)

/** Чистое состояние пикера + его логика — без `Terminal`, чтобы VM тестировалась offline. */
data class PickerState(val options: List<String>, val cursor: Int) {
    fun moved(delta: Int) = copy(cursor = (cursor + delta).mod(options.size))

    /** Пустой ввод → текущий пункт, цифра 1..N → её индекс, иначе `null` (мимо). */
    fun selectionIndex(text: String): Int? = when {
        text.isEmpty() -> cursor
        (text.toIntOrNull() ?: 0) in 1..options.size -> text.toInt() - 1
        else -> null
    }
}

/**
 * Эмитит и обновляет состояние; вся логика переходов — в [onIntent], без
 * отдельного reducer'а. Explicit backing fields: наружу `StateFlow`/`ReceiveChannel`,
 * внутри класса — мутабельные `MutableStateFlow`/`Channel`.
 */
class ChatViewModel(
    private val profiles: List<String> = listOf("admin", "user", "owner"),
) {
    val state: StateFlow<ChatUiState>
        field = MutableStateFlow(ChatUiState())

    val effects: ReceiveChannel<ChatEffect>
        field = Channel<ChatEffect>(Channel.BUFFERED)

    fun onIntent(intent: ChatIntent) = when (intent) {
        ChatIntent.CursorUp -> moveCursor(-1)
        ChatIntent.CursorDown -> moveCursor(+1)
        ChatIntent.Cancel -> state.update { it.copy(picker = null) }
        is ChatIntent.Submit -> submit(intent.text)
    }

    private fun moveCursor(delta: Int) = state.update { s ->
        s.copy(picker = s.picker?.moved(delta))
    }

    private fun submit(text: String) {
        // Пикер закрыт → ввод трактуем как команду/сообщение.
        if (state.value.picker == null) {
            when (text) {
                "/profiles" -> state.update { it.copy(picker = PickerState(profiles, cursor = 0)) }
                "/exit" -> effects.trySend(ChatEffect.Exit) // не внутри update{}: лямбда CAS-ретрайнется
                else -> state.update { it.copy(lines = it.lines + KotterView.SimpleListLine(text)) }
            }
            return
        }
        // Пикер открыт → ввод трактуем как выбор.
        state.update { s ->
            val p = s.picker ?: return@update s
            val idx = p.selectionIndex(text) ?: return@update s.copy(picker = null)
            s.copy(picker = null, lines = s.lines + KotterView.ProfileChangedLine(p.options[idx]))
        }
    }
}

fun runMviChat(vm: ChatViewModel) = session {
    val widgets = Terminal(ansiLevel = AnsiLevel.NONE, width = 60)
    var ui by liveVarOf(vm.state.value)
    val work = CoroutineScope(Dispatchers.Default)
    work.launch { vm.state.collect { ui = it } } // StateFlow → перерисовка Kotter

    section {
        ui.lines.forEach { it.renderIn(this) }
        text("> "); input()
        textLine()
        // picker/stats нуждаются в Terminal — строим KotterView из чистого состояния здесь.
        // onSelected не нужен: выбор обрабатывает VM, пикер тут только для рендера.
        val bottom = ui.picker
            ?.let { KotterView.ProfilePicker(widgets, it.options, it.cursor, onSelected = {}) }
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

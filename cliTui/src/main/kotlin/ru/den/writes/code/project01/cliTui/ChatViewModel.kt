package ru.den.writes.code.project01.cliTui

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * UI-agnostic ядро чата (MVI). Не знает ни про Kotter, ни про Mordant, ни про
 * plain-вывод — только состояние, интенты и one-shot эффекты. Любой вид
 * ([runMviChat] / [runPlainChat]) шлёт интенты и рендерит [ChatUiState] по-своему.
 */

/** Что вид шлёт в VM. Семантика, а не сырые клавиши — VM не знает про Kotter. */
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

/** Строка ленты — чистые данные, без привязки к движку рендера. */
sealed interface ChatLine {
    data class Entered(val text: String) : ChatLine
    data class Selected(val text: String) : ChatLine
}

/** Состояние экрана: лента + (опционально) открытый пикер. */
data class ChatUiState(
    val lines: List<ChatLine> = emptyList(),
    val picker: PickerState? = null,
)

/** Чистое состояние пикера + его логика — без Terminal/Kotter, тестируется offline. */
data class PickerState(val options: List<String>, val cursor: Int) {
    fun moved(delta: Int) = copy(cursor = (cursor + delta).mod(options.size))

    /** Пустой ввод → текущий пункт, цифра 1..N → её индекс, иначе null (мимо). */
    fun selectionIndex(text: String): Int? = when {
        text.isEmpty() -> cursor
        (text.toIntOrNull() ?: 0) in 1..options.size -> text.toInt() - 1
        else -> null
    }
}

/**
 * Эмитит и обновляет состояние; вся логика переходов — в [onIntent], без отдельного
 * reducer'а. Explicit backing fields: наружу StateFlow/ReceiveChannel, внутри класса
 * — мутабельные MutableStateFlow/Channel. Единственный писатель состояния, поэтому
 * конкурентные коллекторы клавиш Kotter не дерутся за общую var.
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
                else -> state.update { it.copy(lines = it.lines + ChatLine.Entered(text)) }
            }
            return
        }
        // Пикер открыт → ввод трактуем как выбор.
        state.update { s ->
            val p = s.picker ?: return@update s
            val idx = p.selectionIndex(text) ?: return@update s.copy(picker = null)
            s.copy(picker = null, lines = s.lines + ChatLine.Selected(p.options[idx]))
        }
    }
}

package ru.den.writes.code.project01.cliTui

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Panel
import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.input
import com.varabyte.kotter.foundation.input.onInputEntered
import com.varabyte.kotter.foundation.input.onKeyPressed
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.runUntilSignal
import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.foundation.text.cyan
import com.varabyte.kotter.foundation.text.green
import com.varabyte.kotter.foundation.text.magenta
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.render.RenderScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Открытый список опций интерактивной команды (напр. выбор профиля). */
private data class ProfilePicker(val options: List<String>, val current: String, val cursor: Int)

/**
 * Combo: Kotter — интерактивный каркас (ввод/цикл/перерисовка через `liveVar`),
 * Mordant — движок виджетов (рендер в строку с [AnsiLevel.NONE], цвет — Kotter).
 *
 * Демонстрирует интерактивную команду `/profiles`:
 *  - команда показывает таблицу опций под лентой, над stats;
 *  - `↑`/`↓` двигают выбор, `Enter` (или цифра) применяет, `Esc` отменяет;
 *  - применённый выбор уходит в ленту отдельной строкой (magenta);
 *  - если вместо выбора отправить сообщение — таблица просто исчезает.
 */
fun runComboChat() = session {
    val responder = DemoResponder()
    val work = CoroutineScope(Dispatchers.Default)
    // Mordant без ANSI: отдаёт чистую рамку, цвет накладывает Kotter.
    val widgets = Terminal(ansiLevel = AnsiLevel.NONE, width = 60)

    var messages by liveVarOf(emptyList<Msg>())
    var streaming by liveVarOf<String?>(null)
    var busy by liveVarOf(false)
    var sessionPrompt by liveVarOf(0)
    var sessionOutput by liveVarOf(0)
    var picker by liveVarOf<ProfilePicker?>(null)
    var activeProfile by liveVarOf(responder.profiles.first())

    fun applyPick(p: ProfilePicker, index: Int) {
        val chosen = p.options[index]
        messages = messages + Msg(
            Msg.Role.SELECTION,
            if (chosen == p.current) "профиль остался: «$chosen»" else "профиль: «${p.current}» → «$chosen»",
        )
        activeProfile = chosen
        picker = null
    }

    section {
        text("Combo — "); cyan { text("Kotter") }; text(" + "); yellow { text("Mordant") }
        textLine("; /profiles — профиль, /exit — выход")
        textLine()
        messages.forEach { m ->
            when (m.role) {
                Msg.Role.USER -> green { textLine("you       │ ${m.text}") }
                Msg.Role.ASSISTANT -> cyan { assistantLines("│", m.text) }
                Msg.Role.STATS -> yellow { textLine("stats     │ ${m.text}") }
                Msg.Role.SELECTION -> magenta { textLine("• ${m.text}") }
            }
        }
        streaming?.let { s -> cyan { assistantLines("▌", s) } }
        textLine()
        // В одной области — ЛИБО пикер (когда открыт), ЛИБО stats: переключение
        // нагляднее, чем две таблицы сразу. Сам выбор остаётся в ленте строкой.
        val openPicker = picker
        if (openPicker != null) {
            val body = openPicker.options.mapIndexed { i, opt ->
                val pointer = if (i == openPicker.cursor) "▶" else " "
                val mark = if (opt == openPicker.current) "  ← активный" else ""
                "$pointer ${i + 1}. $opt$mark"
            }.joinToString("\n")
            val box = widgets.render(Panel(content = body, title = "профили  ↑↓ · Enter · Esc"))
            magenta { box.trimEnd().lineSequence().forEach { textLine(it) } }
        } else {
            val used = sessionPrompt + sessionOutput
            val pct = used.toDouble() / CONTEXT_WINDOW * 100.0
            val panel = widgets.render(
                Panel(
                    content = "session: prompt=$sessionPrompt  output=$sessionOutput  total=$used\n" +
                        "context: $used / $CONTEXT_WINDOW (${"%.1f".format(pct)}%)",
                    title = "stats",
                ),
            )
            yellow { panel.trimEnd().lineSequence().forEach { textLine(it) } }
        }
        // Поле ввода — последним, сразу под виджетом: курсору нечем наезжать.
        text("> "); input()
    }.runUntilSignal {
        // Навигация по пикеру стрелками; Esc — отмена. Enter обрабатывается ниже
        // в onInputEntered (там же различаются «применить выбор» и «отправить»).
        onKeyPressed {
            val p = picker ?: return@onKeyPressed
            when (key) {
                Keys.Up -> picker = p.copy(cursor = (p.cursor - 1 + p.options.size) % p.options.size)
                Keys.Down -> picker = p.copy(cursor = (p.cursor + 1) % p.options.size)
                Keys.Escape -> picker = null
            }
        }
        onInputEntered {
            val entered = input.trim()
            clearInput()
            val p = picker
            when {
                entered == "/exit" || entered == "/quit" -> signal()
                entered == "/profiles" || entered == "/profile-list" ->
                    picker = ProfilePicker(
                        responder.profiles,
                        activeProfile,
                        responder.profiles.indexOf(activeProfile).coerceAtLeast(0),
                    )
                // Пикер открыт: пустой Enter применяет выделенное, цифра — N-й пункт.
                p != null && entered.isEmpty() -> applyPick(p, p.cursor)
                p != null && (entered.toIntOrNull() ?: 0) in 1..p.options.size -> applyPick(p, entered.toInt() - 1)
                // Любое сообщение: пикер (если был) исчезает, добавляется обычный turn.
                entered.isNotEmpty() && !busy -> {
                    picker = null
                    busy = true
                    work.launch {
                        messages = messages + Msg(Msg.Role.USER, entered)
                        val pTok = responder.estimateTokens(entered)
                        sessionPrompt += pTok
                        val sb = StringBuilder()
                        streaming = ""
                        responder.stream(entered).collect { word ->
                            if (sb.isNotEmpty()) sb.append(' ')
                            sb.append(word)
                            streaming = sb.toString()
                        }
                        val reply = sb.toString()
                        val oTok = responder.estimateTokens(reply)
                        sessionOutput += oTok
                        messages = messages + Msg(Msg.Role.ASSISTANT, reply)
                        // Статы за этот ход — отдельной строкой в ленте, как you/assistant.
                        messages = messages + Msg(Msg.Role.STATS, "prompt=$pTok  output=$oTok  total=${pTok + oTok}")
                        streaming = null
                        busy = false
                    }
                }
            }
        }
    }
    work.cancel()
}

/**
 * Реплика ассистента, возможно многострочная: первая строка с префиксом
 * `assistant <marker>`, продолжения выровнены под колонкой `│`. [marker] —
 * `▌` для стрима в процессе, `│` для готового ответа.
 */
private fun RenderScope.assistantLines(marker: String, text: String) {
    val lines = text.split("\n")
    textLine("assistant $marker ${lines.first()}")
    lines.drop(1).forEach { textLine("          │ $it") }
}

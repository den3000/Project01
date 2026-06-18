package ru.den.writes.code.project01.cliTui

import com.varabyte.kotter.foundation.input.input
import com.varabyte.kotter.foundation.input.onInputEntered
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.runUntilSignal
import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.foundation.text.black
import com.varabyte.kotter.foundation.text.cyan
import com.varabyte.kotter.foundation.text.green
import com.varabyte.kotter.foundation.text.magenta
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Kotter-прототип. Declarative без Compose: `section { }` — чистая функция
 * рендера от состояния (`liveVarOf`), `runUntilSignal { }` — петля логики.
 * Любое изменение liveVar перерисовывает секцию, в т.ч. из фоновой корутины —
 * поэтому стрим заглушки крутим в отдельном [work] scope.
 */
fun runKotterChat() = session {
    val responder = DemoResponder()
    val work = CoroutineScope(Dispatchers.Default)

    var messages by liveVarOf(emptyList<Msg>())
    var streaming by liveVarOf<String?>(null)
    var busy by liveVarOf(false)
    var turnPrompt by liveVarOf(0)
    var turnOutput by liveVarOf(0)
    var sessionPrompt by liveVarOf(0)
    var sessionOutput by liveVarOf(0)

    section {
        black(isBright = true) { textLine("Kotter TUI — печатай и жми Enter; /exit для выхода") }
        textLine()
        messages.forEach { m ->
            when (m.role) {
                Msg.Role.USER -> green { textLine("you       │ ${m.text}") }
                Msg.Role.ASSISTANT -> cyan { textLine("assistant │ ${m.text}") }
                Msg.Role.STATS -> yellow { textLine("stats     │ ${m.text}") }
                Msg.Role.SELECTION -> magenta { textLine("• ${m.text}") }
            }
        }
        streaming?.let { s -> cyan { textLine("assistant ▌ $s") } }
        textLine()
        val used = sessionPrompt + sessionOutput
        val pct = used.toDouble() / CONTEXT_WINDOW * 100.0
        yellow {
            textLine("<".repeat(58))
            textLine("turn:    prompt=$turnPrompt  output=$turnOutput  total=${turnPrompt + turnOutput}")
            textLine("session: prompt=$sessionPrompt  output=$sessionOutput  total=$used")
            textLine("context: $used / $CONTEXT_WINDOW (${"%.1f".format(pct)}%)")
            textLine("<".repeat(58))
        }
        textLine()
        // Поле ввода — последним, внизу (иначе footer наезжает на строку ввода).
        text("> "); input()
    }.runUntilSignal {
        onInputEntered {
            val userText = input.trim()
            clearInput()
            when {
                userText == "/exit" || userText == "/quit" -> signal()
                userText.isNotEmpty() && !busy -> {
                    busy = true
                    work.launch {
                        messages = messages + Msg(Msg.Role.USER, userText)
                        turnPrompt = responder.estimateTokens(userText)
                        sessionPrompt += turnPrompt
                        val sb = StringBuilder()
                        streaming = ""
                        responder.stream(userText).collect { word ->
                            if (sb.isNotEmpty()) sb.append(' ')
                            sb.append(word)
                            streaming = sb.toString()
                        }
                        val reply = sb.toString()
                        messages = messages + Msg(Msg.Role.ASSISTANT, reply)
                        turnOutput = responder.estimateTokens(reply)
                        sessionOutput += turnOutput
                        streaming = null
                        busy = false
                    }
                }
            }
        }
    }
    work.cancel()
}

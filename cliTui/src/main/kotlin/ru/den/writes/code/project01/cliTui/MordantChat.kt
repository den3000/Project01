package ru.den.writes.code.project01.cliTui

import com.github.ajalt.mordant.animation.textAnimation
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Panel
import kotlinx.coroutines.runBlocking

/**
 * Mordant-прототип. Mordant — про богатый ВЫВОД, а не про полноэкранный
 * event-loop, поэтому цикл императивный: [Terminal.readLineOrNull] для ввода,
 * [textAnimation] перерисовывает строку ответа во время «стрима», [Panel] —
 * footer со статистикой. Вывод инлайновый — естественная для Mordant модель.
 */
fun runMordantChat() {
    val t = Terminal()
    val responder = DemoResponder()
    var sessionPrompt = 0
    var sessionOutput = 0

    t.println(TextColors.gray("Mordant TUI — печатай и жми Enter; /exit для выхода"))
    while (true) {
        t.print(TextColors.green("you > "))
        val line = t.readLineOrNull(false)?.trim() ?: break
        when {
            line == "/exit" || line == "/quit" -> break
            line.isEmpty() -> continue
        }
        val turnPrompt = responder.estimateTokens(line)
        sessionPrompt += turnPrompt

        // Живой стрим: textAnimation перерисовывает одну область на каждом слове.
        val sb = StringBuilder()
        val anim = t.textAnimation<String> { TextColors.cyan("assistant ▌ $it") }
        runBlocking {
            responder.stream(line).collect { word ->
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(word)
                anim.update(sb.toString())
            }
        }
        anim.clear()
        val reply = sb.toString()
        t.println(TextColors.cyan("assistant │ $reply"))
        val turnOutput = responder.estimateTokens(reply)
        sessionOutput += turnOutput

        val used = sessionPrompt + sessionOutput
        val pct = used.toDouble() / CONTEXT_WINDOW * 100.0
        t.println(
            Panel(
                content = TextColors.yellow(
                    "turn:    prompt=$turnPrompt  output=$turnOutput  total=${turnPrompt + turnOutput}\n" +
                        "session: prompt=$sessionPrompt  output=$sessionOutput  total=$used\n" +
                        "context: $used / $CONTEXT_WINDOW (${"%.1f".format(pct)}%)",
                ),
                title = "stats",
            ),
        )
    }
}

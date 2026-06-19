package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Panel
import com.varabyte.kotter.foundation.input.input
import com.varabyte.kotter.foundation.input.onInputEntered
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.render.aside
import com.varabyte.kotter.foundation.runUntilSignal
import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.foundation.text.cyan
import com.varabyte.kotter.foundation.text.green
import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.render.RenderScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.den.writes.code.project01.cliJvm.ChannelIntentSource
import ru.den.writes.code.project01.cliJvm.SessionStatsSnapshot
import ru.den.writes.code.project01.cliJvm.SessionViewModel
import ru.den.writes.code.project01.cliJvm.UiIntent
import ru.den.writes.code.project01.cliJvm.UiLine
import ru.den.writes.code.project01.cliJvm.agentTag
import ru.den.writes.code.project01.cliJvm.parseSlashCommand

/** Column width of the wrapped-reply prefix, e.g. `"assistant │ "`. */
private const val WRAP_PREFIX_WIDTH = 12

/**
 * Kotter + Mordant terminal UI over [SessionViewModel]. The transcript scrolls
 * via Kotter `aside` (printed once → no flicker); the live `section` holds only
 * the bottom block — a busy hint, the Mordant stats panel, and the input line.
 * Keystrokes become [UiIntent]s pushed into a [ChannelIntentSource] that the
 * view-model's loop pulls from — a single writer of state, so the concurrent
 * Kotter input collectors never race.
 *
 * Minimal first cut: no navigable picker, no streaming; commands are typed.
 */
internal class TuiView(private val multiAgent: Boolean) {

    fun run(vm: SessionViewModel, source: ChannelIntentSource) = session {
        // Mordant renders the stats panel to a plain box-drawing string
        // (no ANSI of its own); Kotter owns the screen and the colour.
        val widgets = Terminal(ansiLevel = AnsiLevel.NONE, width = 80)
        var ui by liveVarOf(vm.state.value)
        // Kotter's MainRenderScope.width is the authority in raw mode
        // (Mordant's Terminal().size lies there). Captured each frame.
        var width = 80
        val work = CoroutineScope(Dispatchers.Default)

        section {
            width = this.width
            if (ui.busy) yellow { textLine("… thinking") }
            ui.stats?.let { renderStats(widgets, it) }
            text("> "); input()
        }.runUntilSignal {
            var printed = 0
            work.launch {
                vm.state.collect { state ->
                    state.lines.drop(printed).forEach { line -> aside { renderLine(line, width) } }
                    printed = state.lines.size
                    ui = state
                }
            }
            work.launch {
                vm.run(source)
                signal()
            }
            onInputEntered {
                val text = input.trim()
                clearInput()
                toIntent(text)?.let { source.offer(it) }
            }
        }
        work.cancel()
        source.close()
    }

    private fun RenderScope.renderLine(line: UiLine, width: Int) {
        when (line) {
            is UiLine.Turn -> {
                val o = line.outcome
                if (multiAgent) cyan { textLine(agentTag(o.profileName, o.modelId)) }
                wrapWords("assistant", o.reply, width).forEach { green { textLine(it) } }
            }
            is UiLine.Error -> red { textLine("⚠ ${line.reason}") }
            is UiLine.Notice -> yellow { textLine(line.text) }
        }
    }

    private fun RenderScope.renderStats(terminal: Terminal, s: SessionStatsSnapshot) {
        val content = "turns=${s.turns}  tokens=${s.totalTokens}  cost=$%.5f".format(s.costUsd)
        val panel = terminal.render(Panel(content = content, title = "session"))
        yellow { panel.trimEnd().lineSequence().forEach { textLine(it) } }
    }
}

/**
 * Classify a typed line into a [UiIntent]: `/exit`|`/quit` → Exit, `/reuse` →
 * Reuse, a recognised `/`-command → SlashCommand, anything else → Submit.
 * Blank input → null (ignored). Shares [parseSlashCommand] with the stdin REPL.
 */
internal fun toIntent(text: String): UiIntent? = when {
    text.isEmpty() -> null
    text.equals("/exit", ignoreCase = true) || text.equals("/quit", ignoreCase = true) -> UiIntent.Exit
    text.equals("/reuse", ignoreCase = true) -> UiIntent.Reuse
    else -> parseSlashCommand(text)?.let { UiIntent.SlashCommand(it) } ?: UiIntent.Submit(text)
}

/**
 * Word-wrap [text] to [width], prefixing the first line with `"[label] │ "` and
 * indenting continuations under the bar so the reply reads as one column. Pure
 * — unit-tested without a terminal.
 */
internal fun wrapWords(label: String, text: String, width: Int): List<String> {
    val prefix = label.padEnd(WRAP_PREFIX_WIDTH - 2) + "│ "
    val indent = " ".repeat(WRAP_PREFIX_WIDTH)
    val avail = (width - WRAP_PREFIX_WIDTH).coerceAtLeast(1)
    val lines = mutableListOf<String>()
    val cur = StringBuilder()
    for (word in text.split(' ')) {
        when {
            cur.isEmpty() -> cur.append(word)
            cur.length + 1 + word.length <= avail -> cur.append(' ').append(word)
            else -> {
                lines.add(cur.toString())
                cur.setLength(0)
                cur.append(word)
            }
        }
    }
    lines.add(cur.toString())
    return lines.mapIndexed { i, l -> if (i == 0) prefix + l else indent + l }
}

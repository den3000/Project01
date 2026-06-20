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
import com.varabyte.kotter.foundation.text.magenta
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
import ru.den.writes.code.project01.cliJvm.TurnResult
import ru.den.writes.code.project01.cliJvm.UiIntent
import ru.den.writes.code.project01.cliJvm.UiLine
import ru.den.writes.code.project01.cliJvm.agentTag
import ru.den.writes.code.project01.cliJvm.formatCost
import ru.den.writes.code.project01.cliJvm.parseSlashCommand
import ru.den.writes.code.project01.cliJvm.formatSessionTokens
import ru.den.writes.code.project01.cliJvm.formatTurnTokens
import ru.den.writes.code.project01.shared.pricing.PricingRegistry

/** Column width of the wrapped-reply prefix, e.g. `"assistant │ "`. */
private const val WRAP_PREFIX_WIDTH = 12

/** Cap on the wrap column so wide terminals keep a readable fixed format. */
private const val MAX_CONTENT_WIDTH = 120

/**
 * Kotter + Mordant terminal UI over [SessionViewModel]. The transcript scrolls
 * via Kotter `aside` (printed once → no flicker); the live `section` holds only
 * the bottom block — a busy hint, the Mordant session panel, and the input
 * line. Keystrokes become [UiIntent]s pushed into a [ChannelIntentSource] that
 * the view-model's loop pulls from — a single writer of state, so the
 * concurrent Kotter input collectors never race.
 *
 * Lines are columnar: `you │ …` / `assistant │ …` / `turn │ …`, wrapped by
 * word to a fixed width with continuations aligned under the bar. Minimal
 * first cut: no navigable picker, no streaming; commands are typed.
 */
internal class TuiView(private val multiAgent: Boolean) {

    fun run(vm: SessionViewModel, source: ChannelIntentSource) = session {
        // Mordant renders the panel to a plain box-drawing string (no ANSI of
        // its own); Kotter owns the screen and the colour.
        val widgets = Terminal(ansiLevel = AnsiLevel.NONE, width = MAX_CONTENT_WIDTH)
        var ui by liveVarOf(vm.state.value)
        // Wrap column: Kotter's MainRenderScope.width is the authority in raw
        // mode (Mordant's Terminal().size lies), capped for a fixed format.
        var width = MAX_CONTENT_WIDTH
        val work = CoroutineScope(Dispatchers.Default)

        section {
            width = minOf(this.width, MAX_CONTENT_WIDTH)
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
            is UiLine.User -> wrapWords("you", line.text, width).forEach { cyan { textLine(it) } }
            is UiLine.Turn -> {
                val o = line.outcome
                if (multiAgent) magenta { textLine(agentTag(o.profileName, o.modelId)) }
                wrapWords("assistant", o.reply, width).forEach { green { textLine(it) } }
                renderTurnStats(o, width)
            }
            is UiLine.Error -> red { textLine("⚠ ${line.reason}") }
            is UiLine.Notice -> yellow { textLine(line.text) }
        }
    }

    /** Per-turn footer in the transcript: `turn │ prompt=… output=… total=… cost=…`. */
    private fun RenderScope.renderTurnStats(o: TurnResult.Ok, width: Int) {
        val usage = o.usage ?: return
        val pricing = PricingRegistry.lookup(o.modelId)
        val cost = pricing?.let { PricingRegistry.cost(usage, it) }
        val line = formatTurnTokens(usage) + "  cost=${formatCost(cost, pricing != null)}"
        wrapWords("turn ${o.session?.turns ?: 1}", line, width).forEach { textLine(it) }
    }

    private fun RenderScope.renderStats(terminal: Terminal, s: SessionStatsSnapshot) {
        val content = "turns=${s.turns}  " + formatSessionTokens(s) + "  cost=$%.5f".format(s.costUsd)
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
 * Word-wrap [text] to [width] in a `"[label] │ "` column: continuations indent
 * under the bar so the entry reads as one block. Honours explicit newlines in
 * [text] (so a markdown list keeps its line breaks) and wraps each line by word.
 * Pure — unit-tested without a terminal.
 */
internal fun wrapWords(label: String, text: String, width: Int): List<String> {
    val prefix = label.padEnd(WRAP_PREFIX_WIDTH - 2) + "│ "
    // Continuations repeat the bar so the whole entry reads as one column.
    val indent = " ".repeat(WRAP_PREFIX_WIDTH - 2) + "│ "
    val avail = (width - WRAP_PREFIX_WIDTH).coerceAtLeast(1)
    val wrapped = mutableListOf<String>()
    for (paragraph in text.split('\n')) {
        if (paragraph.isEmpty()) {
            wrapped.add("")
            continue
        }
        val cur = StringBuilder()
        for (word in paragraph.split(' ')) {
            when {
                cur.isEmpty() -> cur.append(word)
                cur.length + 1 + word.length <= avail -> cur.append(' ').append(word)
                else -> {
                    wrapped.add(cur.toString())
                    cur.setLength(0)
                    cur.append(word)
                }
            }
        }
        wrapped.add(cur.toString())
    }
    if (wrapped.isEmpty()) wrapped.add("")
    return wrapped.mapIndexed { i, l -> if (i == 0) prefix + l else indent + l }
}

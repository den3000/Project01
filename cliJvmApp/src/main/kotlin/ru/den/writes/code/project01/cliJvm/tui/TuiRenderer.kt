package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.input.input
import com.varabyte.kotter.foundation.input.onInputEntered
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.render.aside
import com.varabyte.kotter.foundation.runUntilSignal
import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.den.writes.code.project01.cliJvm.ChannelIntentSource
import ru.den.writes.code.project01.cliJvm.SessionViewModel
import ru.den.writes.code.project01.cliJvm.UiIntent
import ru.den.writes.code.project01.cliJvm.UiLine
import ru.den.writes.code.project01.cliJvm.parseSlashCommand

/**
 * Kotter + Mordant renderer over [SessionViewModel]. The transcript scrolls via
 * Kotter `aside` (printed once → no flicker); the live `section` holds only the
 * bottom block — a busy hint, the Mordant session panel, and the input line.
 * Keystrokes become [UiIntent]s pushed into a [ChannelIntentSource] the
 * view-model's loop pulls from — a single writer of state, so the concurrent
 * Kotter input collectors never race.
 *
 * It owns no rendering itself: each transcript [UiLine] maps to a [TuiView]
 * variant via [toTuiView] and draws through `renderIn`.
 */
internal class TuiRenderer {

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
            ui.stats?.let { SessionPanelTuiView(it).renderIn(this, widgets, width) }
            text("> "); input()
        }.runUntilSignal {
            var printed = 0
            work.launch {
                vm.state.collect { state ->
                    state.lines.drop(printed).forEach { line -> aside { line.toTuiView().renderIn(this, widgets, width) } }
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
}

/** A transcript [UiLine] → its TUI renderer. */
private fun UiLine.toTuiView(): TuiView = when (this) {
    is UiLine.User -> UserTuiView(text)
    is UiLine.Assistant -> AssistantTuiView(reply, agent)
    is UiLine.Turn -> TurnTuiView(usage, modelId, session)
    is UiLine.Judge -> JudgeTuiView(judgeModelId, violations)
    is UiLine.Stage -> StageTuiView(advance)
    is UiLine.Error -> ErrorTuiView(reason)
    is UiLine.Notice -> NoticeTuiView(text)
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

package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.input
import com.varabyte.kotter.foundation.input.onInputEntered
import com.varabyte.kotter.foundation.input.onKeyPressed
import com.varabyte.kotter.foundation.input.setInput
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
import ru.den.writes.code.project01.cliJvm.Overlay
import ru.den.writes.code.project01.cliJvm.PickerKind
import ru.den.writes.code.project01.cliJvm.SessionViewModel
import ru.den.writes.code.project01.cliJvm.UiEffect
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
            // An open overlay takes the panel's slot; the input line stays so a
            // choice can be a number or an arrow + Enter.
            when (val overlay = ui.overlay) {
                null -> ui.stats?.let { SessionPanelTuiView(it).renderIn(this, widgets, width) }
                is Overlay.Picker -> PickerTuiView(overlay).renderIn(this, widgets, width)
                is Overlay.Palette -> PaletteTuiView(overlay).renderIn(this, widgets, width)
            }
            text("> "); input()
        }.runUntilSignal {
            var printed = 0
            // Capture the RunScope so the effect consumer (a background coroutine)
            // can call setInput, which is documented as safe to call asynchronously.
            val run = this
            work.launch {
                // Palette prefill: drop a command stub into the input line. Exit is
                // handled by vm.run returning + signal(), so it's a no-op here.
                for (effect in vm.effects) when (effect) {
                    is UiEffect.Prefill -> run.setInput(effect.text)
                    UiEffect.Exit -> Unit
                }
            }
            work.launch {
                vm.state.collect { state ->
                    state.lines.drop(printed).forEach { line ->
                        val view = line.toTuiView() ?: return@forEach
                        aside { view.renderIn(this, widgets, width) }
                    }
                    printed = state.lines.size
                    ui = state
                }
            }
            work.launch {
                vm.run(source)
                signal()
            }
            // Arrow / Esc drive an open picker; when none is open they're inert
            // so normal typing is untouched. Enter stays on onInputEntered (no
            // race with the input collector).
            onKeyPressed {
                if (ui.overlay == null) return@onKeyPressed
                when (key) {
                    Keys.Up -> source.offer(UiIntent.OverlayUp)
                    Keys.Down -> source.offer(UiIntent.OverlayDown)
                    Keys.Escape -> source.offer(UiIntent.OverlayCancel)
                    else -> Unit
                }
            }
            onInputEntered {
                val text = input.trim()
                clearInput()
                // With an overlay open, even an empty Enter is a selection (the
                // cursor row), so bypass toIntent's blank → null.
                if (ui.overlay != null) source.offer(UiIntent.Submit(text))
                else toIntent(text)?.let { source.offer(it) }
            }
        }
        work.cancel()
        source.close()
    }
}

/** A transcript [UiLine] → its TUI renderer, or null for lines the TUI drops. */
private fun UiLine.toTuiView(): TuiView? = when (this) {
    is UiLine.User -> UserTuiView(text)
    is UiLine.Assistant -> AssistantTuiView(reply, agent)
    is UiLine.Turn -> TurnTuiView(usage, modelId, session)
    is UiLine.Judge -> JudgeTuiView(judgeModelId, violations)
    is UiLine.Stage -> StageTuiView(advance)
    is UiLine.State -> StateTuiView(text)
    is UiLine.Error -> ErrorTuiView(reason)
    is UiLine.Notice -> NoticeTuiView(text)
    // The final summary is dropped here — the live stats panel already shows the totals.
    is UiLine.Summary -> null
}

/**
 * Classify a typed line into a [UiIntent]: `/exit`|`/quit` → Exit, `/reuse` →
 * Reuse, `/help`|`/?` → OpenPalette, an argument-less command that has a picker
 * (`/profile-use`|`/profiles`, `/task`, `/switch`|`/branches`, `/memory-mode`) →
 * OpenPicker, any other recognised `/`-command → SlashCommand, else → Submit. Blank →
 * null (ignored). The bare picker / palette forms are intercepted here, before
 * [parseSlashCommand], so the stdin REPL (which doesn't share this) is untouched.
 */
internal fun toIntent(text: String): UiIntent? = when {
    text.isEmpty() -> null
    text.equals("/exit", ignoreCase = true) || text.equals("/quit", ignoreCase = true) -> UiIntent.Exit
    text.equals("/reuse", ignoreCase = true) -> UiIntent.Reuse
    text.equals("/help", ignoreCase = true) || text == "/?" -> UiIntent.OpenPalette
    text.equals("/profile-use", ignoreCase = true) || text.equals("/profiles", ignoreCase = true) ->
        UiIntent.OpenPicker(PickerKind.Profile)
    text.equals("/task", ignoreCase = true) -> UiIntent.OpenPicker(PickerKind.Task)
    text.equals("/switch", ignoreCase = true) || text.equals("/branches", ignoreCase = true) ->
        UiIntent.OpenPicker(PickerKind.Branch)
    text.equals("/memory-mode", ignoreCase = true) -> UiIntent.OpenPicker(PickerKind.MemoryMode)
    else -> parseSlashCommand(text)?.let { UiIntent.SlashCommand(it) } ?: UiIntent.Submit(text)
}

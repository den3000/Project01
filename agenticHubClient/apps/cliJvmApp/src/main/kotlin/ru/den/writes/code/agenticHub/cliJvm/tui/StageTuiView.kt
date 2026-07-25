package ru.den.writes.code.agenticHub.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.render.RenderScope
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.StageAdvance

/**
 * A task-stage FSM move as a `"task │ …"` column (yellow). Renders nothing for
 * [StageAdvance.None] — the view-model only emits a [ru.den.writes.code.agenticHub.cliJvm.UiLine.Stage]
 * when something actually happened, so that branch is defensive.
 */
internal data class StageTuiView(val advance: StageAdvance) : TuiView {
    override fun RenderScope.render(terminal: Terminal, width: Int) {
        val text = stageLine(advance) ?: return
        wrapWords("task", text, width).forEach { yellow { textLine(it) } }
    }
}

/**
 * The text of the stage line, or null when there is nothing to say. Split out of
 * [StageTuiView.render] because rendering needs a Kotter [RenderScope] and the wording
 * does not — this is the half a test can read, mirroring `StagePlainView.stderr()`.
 */
internal fun stageLine(advance: StageAdvance): String? = when (advance) {
    StageAdvance.None -> null
    is StageAdvance.Advanced ->
        "stage: ${advance.from?.keyword ?: "(none)"} → ${advance.to.keyword} (auto)"
    is StageAdvance.Rejected ->
        "model proposed ${advance.from?.keyword ?: "(none)"} → ${advance.proposed.keyword}, " +
            "not allowed (allowed: ${advance.allowed.joinToString(", ") { it.keyword }}) — ignored"
    is StageAdvance.Repeated ->
        "model re-signalled ${advance.stage.keyword} — already there, no move"
}

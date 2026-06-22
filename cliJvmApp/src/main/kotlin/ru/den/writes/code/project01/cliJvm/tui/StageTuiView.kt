package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.render.RenderScope
import ru.den.writes.code.project01.cliJvm.StageAdvance

/**
 * A task-stage FSM move as a `"task │ …"` column (yellow). Renders nothing for
 * [StageAdvance.None] — the view-model only emits a [ru.den.writes.code.project01.cliJvm.UiLine.Stage]
 * when something actually happened, so that branch is defensive.
 */
internal data class StageTuiView(val advance: StageAdvance) : TuiView {
    override fun RenderScope.render(terminal: Terminal, width: Int) {
        val text = when (advance) {
            StageAdvance.None -> return
            is StageAdvance.Advanced ->
                "stage: ${advance.from?.keyword ?: "(none)"} → ${advance.to.keyword} (auto)"
            is StageAdvance.Rejected ->
                "model proposed ${advance.from?.keyword ?: "(none)"} → ${advance.proposed.keyword}, " +
                    "not allowed (allowed: ${advance.allowed.joinToString(", ") { it.keyword }}) — ignored"
        }
        wrapWords("task", text, width).forEach { yellow { textLine(it) } }
    }
}

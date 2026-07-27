package ru.den.writes.code.agenticHub.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.render.RenderScope
import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome

/**
 * What the task FSM did with a turn the task paid for, as an `"fsm │ …"` column (yellow).
 * Its own column rather than `task`: the stage line reports where the task moved, this one
 * reports whether the task still exists, and a reader scanning a run should not have to tell
 * them apart by wording.
 */
internal data class FsmTuiView(val outcome: RetryOutcome) : TuiView {
    override fun RenderScope.render(terminal: Terminal, width: Int) {
        val text = fsmLine(outcome) ?: return
        wrapWords("fsm", text, width).forEach { yellow { textLine(it) } }
    }
}

/**
 * The text of the verdict line, or null when there is nothing to say. Split out of
 * [FsmTuiView.render] because rendering needs a Kotter [RenderScope] and the wording does
 * not — this is the half a test can read, mirroring `FsmPlainView.stderr()`.
 */
internal fun fsmLine(outcome: RetryOutcome): String? = when (outcome) {
    is RetryOutcome.Retried -> null
    is RetryOutcome.Restarted ->
        "task restarted from the top — attempt ${outcome.task.taskRetryState.attempt + 1}"
    is RetryOutcome.GaveUp ->
        "task gave up at ${outcome.task.stage.keyword} — out of attempts (${outcome.reason.name})"
}

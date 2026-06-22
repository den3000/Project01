package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Panel
import com.varabyte.kotter.foundation.text.magenta
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope
import ru.den.writes.code.project01.cliJvm.PickerState

/**
 * The open modal picker pinned in the bottom block (not a transcript line): its
 * options as a numbered list with a ▶ cursor, drawn as a Mordant `Panel` and
 * coloured magenta by Kotter to stand apart from the yellow stats panel it
 * replaces. [optionLines] is pure, so the row formatting is unit-testable off an
 * instance (the `session`/`Panel` machinery needs a real terminal).
 */
internal data class PickerTuiView(val picker: PickerState) : TuiView {
    /** One numbered row per option; the cursor row is marked with ▶. */
    fun optionLines(): List<String> = picker.options.mapIndexed { i, opt ->
        val pointer = if (i == picker.cursor) "▶" else " "
        "$pointer ${i + 1}. $opt"
    }

    override fun RenderScope.render(terminal: Terminal, width: Int) {
        val panel = terminal.render(Panel(content = optionLines().joinToString("\n"), title = picker.kind.title))
        magenta { panel.trimEnd().lineSequence().forEach { textLine(it) } }
    }
}

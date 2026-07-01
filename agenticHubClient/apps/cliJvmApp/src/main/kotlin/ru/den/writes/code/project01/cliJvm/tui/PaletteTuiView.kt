package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Panel
import com.varabyte.kotter.foundation.text.magenta
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope
import ru.den.writes.code.project01.cliJvm.Overlay

/**
 * The command palette pinned in the bottom block: each `/`-command as a numbered
 * `name — help` row with a ▶ cursor, drawn as a Mordant `Panel` and coloured
 * magenta (same modal treatment as [PickerTuiView]). [optionLines] is pure, so
 * the row formatting is unit-testable off an instance.
 */
internal data class PaletteTuiView(val palette: Overlay.Palette) : TuiView {
    /** One numbered `name — help` row per command; the cursor row is marked with ▶. */
    fun optionLines(): List<String> = palette.entries.mapIndexed { i, entry ->
        val pointer = if (i == palette.cursor) "▶" else " "
        "$pointer ${i + 1}. ${entry.name} — ${entry.help}"
    }

    override fun RenderScope.render(terminal: Terminal, width: Int) {
        val panel = terminal.render(Panel(content = optionLines().joinToString("\n"), title = "commands  ↑↓ · Enter · Esc"))
        magenta { panel.trimEnd().lineSequence().forEach { textLine(it) } }
    }
}

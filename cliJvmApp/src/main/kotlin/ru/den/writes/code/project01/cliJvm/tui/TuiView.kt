package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.runtime.render.RenderScope

/** Column width of the wrapped prefix, e.g. `"assistant │ "`. */
internal const val WRAP_PREFIX_WIDTH = 12

/** Cap on the wrap column so wide terminals keep a readable fixed format. */
internal const val MAX_CONTENT_WIDTH = 120

/**
 * Render dictionary for the TUI transcript: each variant knows how to draw
 * itself into a Kotter [RenderScope]. Mordant widgets (Panel) render to a string
 * with `AnsiLevel.NONE` (plain box-drawing); Kotter applies colour from outside
 * and owns the screen. Pure presentation — no state, no orchestration (that
 * lives in `SessionViewModel`). A [ru.den.writes.code.project01.cliJvm.UiLine]
 * maps to one of these via `toTuiView` in `TuiRenderer`.
 */
internal sealed interface TuiView {
    fun RenderScope.render(terminal: Terminal, width: Int)

    /** Bind the receiver and draw — the entry point a driver calls per line. */
    fun renderIn(scope: RenderScope, terminal: Terminal, width: Int) = with(scope) { render(terminal, width) }

    /**
     * Word-wrap [text] to [width] in a `"[label] │ "` column: continuations
     * indent under the bar so the entry reads as one block. Honours explicit
     * newlines (a markdown list keeps its breaks) and wraps each line by word.
     * Shared by every variant; pure, so it's unit-testable off any instance.
     */
    fun wrapWords(label: String, text: String, width: Int): List<String> {
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
}

package ru.den.writes.code.agenticHub.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.text.cyan
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope
import ru.den.writes.code.agenticHub.features.lifecycle.session.ragSourceLines
import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk

/**
 * The retrieved RAG sources as a `"rag │ …"` column (cyan): the `[rag] sources:`
 * header then one `[source › section #id] score=…` per chunk. Continuations align
 * under the bar like every other column.
 */
internal data class RagTuiView(val chunks: List<ScoredChunk>) : TuiView {
    override fun RenderScope.render(terminal: Terminal, width: Int) {
        wrapWords("rag", ragSourceLines(chunks).joinToString("\n"), width).forEach { cyan { textLine(it) } }
    }
}

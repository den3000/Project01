package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.text.blue
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope
import ru.den.writes.code.project01.cliJvm.mcpToolLines
import ru.den.writes.code.project01.shared.agent.ExecutedToolCall

/**
 * The MCP tool round-trip as an `"mcp │ …"` column (blue): each `[tool]` call +
 * result, then the `model:` and `prompt:` it was sent back as. Continuations
 * align under the bar like every other column.
 */
internal data class McpTuiView(val calls: List<ExecutedToolCall>, val modelId: String) : TuiView {
    override fun RenderScope.render(terminal: Terminal, width: Int) {
        wrapWords("mcp", mcpToolLines(calls, modelId).joinToString("\n"), width).forEach { blue { textLine(it) } }
    }
}

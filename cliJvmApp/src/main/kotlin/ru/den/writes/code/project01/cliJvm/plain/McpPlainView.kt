package ru.den.writes.code.project01.cliJvm.plain

import ru.den.writes.code.project01.cliJvm.mcpToolLines
import ru.den.writes.code.project01.shared.agent.ExecutedToolCall

/**
 * The MCP tool round-trip as plain stdout lines: each `[tool]` call + result,
 * then the `model:` it went back to and the `prompt:` (= that result) it was
 * sent as. Replaces the old per-call `>>>>` debug echo.
 */
internal data class McpPlainView(val calls: List<ExecutedToolCall>, val modelId: String) : PlainView {
    override fun stdout(): List<String> = mcpToolLines(calls, modelId)
}

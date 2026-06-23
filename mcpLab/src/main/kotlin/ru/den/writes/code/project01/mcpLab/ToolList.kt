package ru.den.writes.code.project01.mcpLab

/** One tool as advertised by an MCP server — only the parts we print. */
data class ToolInfo(val name: String, val description: String?)

/**
 * Renders the tool list for stdout: a `N tool(s):` header followed by one
 * `• name — description` line per tool (name only when there is no description).
 * Pure (no SDK / no IO) so it is unit-tested directly.
 */
fun formatToolList(tools: List<ToolInfo>): String {
    if (tools.isEmpty()) return "0 tools."
    val lines = tools.joinToString("\n") { tool ->
        val desc = tool.description?.takeIf { it.isNotBlank() }
        if (desc != null) "  • ${tool.name} — $desc" else "  • ${tool.name}"
    }
    return "${tools.size} tool(s):\n$lines"
}

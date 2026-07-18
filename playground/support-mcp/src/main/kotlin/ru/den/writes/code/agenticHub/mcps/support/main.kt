package ru.den.writes.code.agenticHub.mcps.support

/**
 * Entry point for support-mcp: an MCP server over stdio exposing a users/tickets fixture
 * to a support-assistant chat.
 *
 * Arguments, passed by the client via `-mcpServer "<bin> <dataRoot>"`:
 * 1. dataRoot — directory holding `users.json` and `tickets.json`. Defaults to the
 *    process working directory when omitted or blank.
 *
 * Runs until the client disconnects (stdin closes).
 */
suspend fun main(args: Array<String>) {
    val dataRoot = args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "."
    runSupportServer(dataRoot)
}

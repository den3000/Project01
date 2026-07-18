package ru.den.writes.code.agenticHub.mcps.support

/**
 * Entry point for support-mcp: an MCP server over stdio exposing a users/tickets fixture
 * to a support-assistant chat.
 *
 * Arguments, passed by the client via `-mcpServer "<bin> <dataRoot> [--dev]"`:
 * 1. dataRoot — directory holding `users.json` and `tickets.json`. Defaults to the
 *    process working directory when omitted or blank.
 * 2. `--dev` (optional, any later position) — developer launch: additionally exposes the
 *    `set_ticket_status` mutator. Its presence is the access gate (a normal support launch
 *    omits it), standing in for a token: who may change tickets is decided by the launch.
 *
 * Runs until the client disconnects (stdin closes).
 */
suspend fun main(args: Array<String>) {
    val positional = args.filterNot { it.startsWith("--") }
    val dataRoot = positional.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "."
    val devMode = args.any { it == "--dev" }
    runSupportServer(dataRoot, devMode)
}

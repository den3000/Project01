package ru.den.writes.code.agenticHub.mcps.ticktick

/**
 * Entry point for ticktick-mcp: an MCP server over stdio exposing TickTick projects and tasks
 * via the official Open API (OAuth2 Bearer).
 *
 * The access token comes from the **environment**, never argv (argv is world-visible in `ps`):
 *   - `TICKTICK_ACCESS_TOKEN` — obtained once via the OAuth2 authorization-code flow (see README).
 * When absent, the server prints a hint to stderr and exits.
 *
 * Spawned by an MCP client as a subprocess; runs until the client disconnects (stdin closes).
 */
suspend fun main() {
    val token = System.getenv("TICKTICK_ACCESS_TOKEN")?.takeIf { it.isNotBlank() }
    if (token == null) {
        System.err.println("[ticktick-mcp] missing credentials: set TICKTICK_ACCESS_TOKEN env var")
        return
    }
    runTicktickServer(token)
}

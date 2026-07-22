package ru.den.writes.code.agenticHub.mcps.atimelogger

/**
 * Entry point for atimelogger-mcp: an MCP server over stdio exposing aTimeLogger time-tracking
 * data (activity types and tracked intervals) to an assistant.
 *
 * Credentials come from the **environment**, never argv (argv is world-visible in `ps`):
 *   - `ATIMELOGGER_USERNAME`, `ATIMELOGGER_PASSWORD` — HTTP Basic against app.atimelogger.com.
 * Both must be set; otherwise the server prints a hint to stderr and exits.
 *
 * Spawned by an MCP client as a subprocess; runs until the client disconnects (stdin closes).
 */
suspend fun main() {
    val username = System.getenv("ATIMELOGGER_USERNAME")?.takeIf { it.isNotBlank() }
    val password = System.getenv("ATIMELOGGER_PASSWORD")?.takeIf { it.isNotBlank() }
    if (username == null || password == null) {
        System.err.println(
            "[atimelogger-mcp] missing credentials: set ATIMELOGGER_USERNAME and ATIMELOGGER_PASSWORD env vars",
        )
        return
    }
    runAtimeloggerServer(username, password)
}

package ru.den.writes.code.agenticHub.mcps.git

/**
 * Entry point for git-mcp: an MCP server over stdio exposing read-only git tools
 * (`current_branch` / `list_files` / `diff`) for one repository. The repo root is the
 * first CLI argument — a client passes it via `-mcpServer "<bin> <repo>"` — and defaults
 * to the process working directory. Runs until the client disconnects (stdin closes).
 */
suspend fun main(args: Array<String>) {
    val repoRoot = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: "."
    runGitServer(repoRoot)
}

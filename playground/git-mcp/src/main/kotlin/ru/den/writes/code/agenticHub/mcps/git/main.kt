package ru.den.writes.code.agenticHub.mcps.git

/**
 * Entry point for git-mcp: an MCP server over stdio exposing read-only git tools
 * (`current_branch` / `list_files` / `changed_files` / `diff`) for one repository.
 *
 * Arguments, passed by the client via `-mcpServer "<bin> <repo> [<base-ref>]"`:
 * 1. repo root — defaults to the process working directory;
 * 2. base ref (optional) — the commit the reviewed work forked from, e.g. a PR's base
 *    SHA. Set it and `diff`/`changed_files` describe that range with no argument from
 *    the model; omit it and they describe the working tree.
 *
 * Runs until the client disconnects (stdin closes).
 */
suspend fun main(args: Array<String>) {
    val repoRoot = args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "."
    val defaultBase = args.getOrNull(1)?.takeIf { it.isNotBlank() }
    runGitServer(repoRoot, defaultBase)
}

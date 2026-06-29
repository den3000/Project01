package ru.den.writes.code.project01.mcps.localfs

/**
 * Entry point for localfs-mcp: an MCP server over stdio exposing the document tools
 * (`append_to_document` / `save_document`). It is meant to be spawned by an MCP client
 * as a subprocess, so it has no modes or flags — any arguments are ignored and the
 * server runs until the client disconnects (stdin closes).
 */
suspend fun main() {
    runFileSystemServer()
}

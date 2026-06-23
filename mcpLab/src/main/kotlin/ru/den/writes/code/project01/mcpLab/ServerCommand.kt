package ru.den.writes.code.project01.mcpLab

/**
 * Default MCP server launched when no arguments are given: the upstream
 * reference "everything" server. No API key, maintained by the MCP team, so it
 * stays a reliable connectivity target.
 */
val DEFAULT_SERVER_COMMAND: List<String> =
    listOf("npx", "-y", "@modelcontextprotocol/server-everything")

/**
 * Resolves the command line used to spawn the MCP server from CLI arguments.
 *
 * No arguments → [DEFAULT_SERVER_COMMAND]. Otherwise the arguments are taken
 * verbatim as the command to run, e.g. `npx -y @dangahagan/weather-mcp@latest`
 * or `java -jar weather-server.jar`. The client code is the same for any
 * server — only the launch command differs.
 */
fun parseServerCommand(args: Array<String>): List<String> =
    if (args.isEmpty()) DEFAULT_SERVER_COMMAND else args.toList()

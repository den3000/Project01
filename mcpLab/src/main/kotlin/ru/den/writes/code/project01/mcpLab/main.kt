package ru.den.writes.code.project01.mcpLab

private val USAGE: String = """
    mcpLab — MCP client: connect to an MCP server over stdio and list its tools.

    Usage:
      mcpLab                  connect to the default server
                              (${DEFAULT_SERVER_COMMAND.joinToString(" ")})
      mcpLab <cmd> [args...]  spawn <cmd> as the MCP server, e.g.
                              mcpLab npx -y @dangahagan/weather-mcp@latest
      mcpLab -h | --help      show this help
""".trimIndent()

suspend fun main(args: Array<String>) {
    if (args.firstOrNull() in setOf("-h", "--help")) {
        println(USAGE)
        return
    }

    val command = parseServerCommand(args)
    println("would connect to: ${command.joinToString(" ")}")
}

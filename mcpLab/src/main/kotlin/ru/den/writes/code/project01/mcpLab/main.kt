package ru.den.writes.code.project01.mcpLab

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.IOException
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

private val USAGE: String = """
    mcpLab — MCP client: connect to an MCP server over stdio and list its tools.

    Usage:
      mcpLab                  connect to the default server
                              (${DEFAULT_SERVER_COMMAND.joinToString(" ")})
      mcpLab <cmd> [args...]  spawn <cmd> as the MCP server, e.g.
                              mcpLab npx -y @dangahagan/weather-mcp@latest
      mcpLab --serve          run the built-in Open-Meteo weather MCP server
      mcpLab -h | --help      show this help
""".trimIndent()

/** Upper bound for the server to start, connect, and answer listTools. */
private const val CONNECT_TIMEOUT_MS = 60_000L

suspend fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "-h", "--help" -> {
            println(USAGE)
            return
        }
        "--serve" -> {
            runWeatherServer()
            return
        }
    }

    val command = parseServerCommand(args)
    System.err.println("[mcpLab] spawning MCP server: ${command.joinToString(" ")}")

    // The subprocess' stdout/stdin carry the JSON-RPC stream; its stderr (logs)
    // is forwarded to ours so server logging can't corrupt the protocol channel.
    val process = try {
        withContext(Dispatchers.IO) {
            ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        }
    } catch (e: IOException) {
        System.err.println("[mcpLab] cannot start server '${command.first()}': ${e.message}")
        exitProcess(1)
    }

    val transport = StdioClientTransport(
        input = process.inputStream.asSource().buffered(),
        output = process.outputStream.asSink().buffered(),
    )
    val client = Client(clientInfo = Implementation(name = "mcpLab", version = "0.1.0"))

    var failed = false
    try {
        // Bound the wait: a server that starts but never speaks MCP (wrong
        // command, crash mid-handshake) must not hang the probe forever.
        withTimeout(CONNECT_TIMEOUT_MS.milliseconds) {
            client.connect(transport)
            val tools = client.listTools().tools.map { ToolInfo(it.name, it.description) }
            println("Connected to: ${command.joinToString(" ")}")
            println(formatToolList(tools))
        }
    } catch (e: TimeoutCancellationException) {
        System.err.println("[mcpLab] timed out after $CONNECT_TIMEOUT_MS ms waiting for the server")
        failed = true
    } catch (e: Exception) {
        System.err.println("[mcpLab] failed: ${e.message}")
        failed = true
    } finally {
        // One-shot probe: force-kill the server subprocess. We deliberately skip
        // the suspend client.close() — some servers don't exit when their stdin
        // closes, so close() can block forever on the stdio reader. The explicit
        // exit below then tears the JVM down regardless of background readers.
        process.destroyForcibly()
    }
    exitProcess(if (failed) 1 else 0)
}

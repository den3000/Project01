package ru.den.writes.code.agenticHub.mcps.atimelogger

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Job
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject

/**
 * Runs the atimelogger MCP server over stdio. Authenticates to the aTimeLogger v2 API with HTTP
 * Basic ([username]/[password]) installed once on the client's `defaultRequest`. stdout is the
 * JSON-RPC channel — every diagnostic goes to stderr so it can't corrupt the protocol stream.
 * Blocks until the client disconnects (stdin closes).
 */
suspend fun runAtimeloggerServer(username: String, password: String) {
    val http = HttpClient(Java) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        defaultRequest { header(HttpHeaders.Authorization, basicAuthHeader(username, password)) }
    }
    val reports = AtimeloggerReports(HttpAtimeloggerApi(http))

    val server = Server(
        serverInfo = Implementation(name = "atimelogger-mcp", version = "0.1.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    )

    server.addTool(
        name = "list_activity_types",
        description = "List the aTimeLogger activity types for this account (name, and color when set). " +
            "Use it to learn the category names that time_by_activity reports time against.",
        inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
    ) { _ ->
        val text = runCatching { reports.listActivityTypes() }
            .getOrElse { "Error fetching activity types: ${it.message}" }
        CallToolResult(content = listOf(TextContent(text)))
    }

    System.err.println("[atimelogger-mcp] atimelogger MCP server ready on stdio (tools: list_activity_types)")
    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered(),
    ) { /* defaults */ }
    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
    http.close()
}

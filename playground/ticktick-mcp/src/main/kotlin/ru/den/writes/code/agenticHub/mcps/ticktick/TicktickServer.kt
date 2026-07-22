package ru.den.writes.code.agenticHub.mcps.ticktick

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
 * Runs the ticktick MCP server over stdio. Authenticates to the TickTick Open API with the OAuth2
 * Bearer [accessToken] installed once on the client's `defaultRequest`. stdout is the JSON-RPC
 * channel — every diagnostic goes to stderr so it can't corrupt the protocol stream. Blocks until
 * the client disconnects (stdin closes).
 */
suspend fun runTicktickServer(accessToken: String) {
    val http = HttpClient(Java) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        defaultRequest { header(HttpHeaders.Authorization, "Bearer $accessToken") }
    }
    val reports = TicktickReports(HttpTicktickApi(http))

    val server = Server(
        serverInfo = Implementation(name = "ticktick-mcp", version = "0.1.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    )

    server.addTool(
        name = "list_projects",
        description = "List the TickTick projects (lists) for this account as 'id  name' per line. " +
            "The id is what the week tools address a project by.",
        inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
    ) { _ ->
        val text = runCatching { reports.listProjects() }
            .getOrElse { "Error fetching projects: ${it.message}" }
        CallToolResult(content = listOf(TextContent(text)))
    }

    System.err.println("[ticktick-mcp] ticktick MCP server ready on stdio (tools: list_projects)")
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

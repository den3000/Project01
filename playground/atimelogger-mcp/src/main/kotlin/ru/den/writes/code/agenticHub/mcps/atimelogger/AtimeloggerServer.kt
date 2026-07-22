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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.ZoneId

/**
 * Runs the atimelogger MCP server over stdio. Authenticates to the aTimeLogger v2 API with HTTP
 * Basic ([username]/[password]) installed once on the client's `defaultRequest`. Date-range
 * tools interpret dates in the `WEEK_TZ` time zone (default: the server's zone). stdout is the
 * JSON-RPC channel — every diagnostic goes to stderr so it can't corrupt the protocol stream.
 * Blocks until the client disconnects (stdin closes).
 */
suspend fun runAtimeloggerServer(username: String, password: String) {
    val zone = System.getenv("WEEK_TZ")?.takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()

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

    server.addTool(
        name = "time_by_activity",
        description = "Total time tracked per activity type over a date range (e.g. one week), sorted by " +
            "time with a total. 'from' is the inclusive start date and 'to' is the EXCLUSIVE end date (the " +
            "day after the last day), both YYYY-MM-DD. Dates are read in the WEEK_TZ zone (default: server zone).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "from",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Inclusive start date, YYYY-MM-DD, e.g. \"2026-07-13\".")
                    },
                )
                put(
                    "to",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Exclusive end date — the day after the last day, YYYY-MM-DD, e.g. \"2026-07-20\".")
                    },
                )
            },
            required = listOf("from", "to"),
        ),
    ) { request ->
        val from = request.arguments?.get("from")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val to = request.arguments?.get("to")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val text = when {
            from == null || to == null -> "Error: 'from' and 'to' (YYYY-MM-DD) are required."
            else -> runCatching {
                reports.timeByActivity(localDateToEpochSeconds(from, zone), localDateToEpochSeconds(to, zone))
            }.getOrElse { "Error fetching time by activity for $from..$to: ${it.message}" }
        }
        CallToolResult(content = listOf(TextContent(text)))
    }

    System.err.println(
        "[atimelogger-mcp] atimelogger MCP server ready on stdio (tools: list_activity_types, time_by_activity)",
    )
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

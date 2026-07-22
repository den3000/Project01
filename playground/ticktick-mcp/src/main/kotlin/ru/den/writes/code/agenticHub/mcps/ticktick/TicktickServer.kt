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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.ZoneId

/**
 * Runs the ticktick MCP server over stdio. Authenticates to the TickTick Open API with the OAuth2
 * Bearer [accessToken] installed once on the client's `defaultRequest`; week snapshots persist
 * under [snapshotRoot]. Date-range tools interpret dates in the `WEEK_TZ` time zone (default: the
 * server's zone). stdout is the JSON-RPC channel — every diagnostic goes to stderr so it can't
 * corrupt the protocol stream. Blocks until the client disconnects (stdin closes).
 */
suspend fun runTicktickServer(accessToken: String, snapshotRoot: String) {
    val zone = System.getenv("WEEK_TZ")?.takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()

    val http = HttpClient(Java) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        defaultRequest { header(HttpHeaders.Authorization, "Bearer $accessToken") }
    }
    val reports = TicktickReports(HttpTicktickApi(http), FileSnapshotStore(snapshotRoot))

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

    server.addTool(
        name = "snapshot_week",
        description = "Snapshot the plan for a week: save every undone task whose due date falls in " +
            "the range, so review_week can later tell what got done (the API can't list completed tasks). " +
            "Run this at the START of the week. 'from' is the inclusive start date and 'to' the EXCLUSIVE " +
            "end date (day after the last day), both YYYY-MM-DD; 'label' names the snapshot, e.g. \"2026-W29\".",
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
                put(
                    "label",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Snapshot name, e.g. \"2026-W29\". Reused by review_week. Defaults to \"<from>_<to>\".")
                    },
                )
            },
            required = listOf("from", "to"),
        ),
    ) { request ->
        val from = request.arguments?.get("from")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val to = request.arguments?.get("to")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val label = request.arguments?.get("label")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val text = when {
            from == null || to == null -> "Error: 'from' and 'to' (YYYY-MM-DD) are required."
            else -> runCatching {
                reports.snapshotWeek(
                    localDateToEpochMillis(from, zone),
                    localDateToEpochMillis(to, zone),
                    label ?: "${from}_$to",
                )
            }.getOrElse { "Error taking snapshot for $from..$to: ${it.message}" }
        }
        CallToolResult(content = listOf(TextContent(text)))
    }

    System.err.println("[ticktick-mcp] ticktick MCP server ready on stdio (tools: list_projects, snapshot_week)")
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

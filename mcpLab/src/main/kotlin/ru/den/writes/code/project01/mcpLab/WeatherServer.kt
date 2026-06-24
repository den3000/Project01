package ru.den.writes.code.project01.mcpLab

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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

/**
 * Runs our own MCP server over stdio, exposing a single tool, `current_weather`,
 * backed by the free Open-Meteo API. stdout is the JSON-RPC channel — every
 * diagnostic goes to stderr so it can't corrupt the protocol stream. Blocks
 * until the client disconnects (stdin closes).
 */
suspend fun runWeatherServer() {
    val http = HttpClient(Java) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    val weather = OpenMeteoClient(http)

    val server = Server(
        serverInfo = Implementation(name = "mcpLab-weather", version = "0.1.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    )

    server.addTool(
        name = "current_weather",
        description = "Get the current weather for a city by name (e.g. \"Paris\").",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "city",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "City name to look up, e.g. \"Paris\" or \"Tokyo\".")
                    },
                )
            },
            required = listOf("city"),
        ),
    ) { request ->
        val city = request.arguments?.get("city")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val text = if (city == null) {
            "Error: the 'city' argument is required."
        } else {
            runCatching { weather.currentWeather(city) }
                .getOrElse { "Error fetching weather for \"$city\": ${it.message}" }
        }
        CallToolResult(content = listOf(TextContent(text)))
    }

    System.err.println("[mcpLab] weather MCP server ready on stdio (tool: current_weather)")
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

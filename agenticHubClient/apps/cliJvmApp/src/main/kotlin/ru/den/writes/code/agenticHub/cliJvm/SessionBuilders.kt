package ru.den.writes.code.agenticHub.cliJvm

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import ru.den.writes.code.agenticHub.features.llm.McpToolRouter
import ru.den.writes.code.agenticHub.features.mcpclient.McpToolClient
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand

/**
 * App-side wiring for a session: the concrete HTTP client and the MCP-client
 * fan-out (the one bit that knows about the [McpToolClient] platform impl). The
 * portable builders (agents/judges/session-id) live in `AgentBuilders` in
 * features:viewModel.
 */

/** Generous request timeout — LLM responses can take a while. */
internal const val REQUEST_TIMEOUT_MS = 300_000L

/**
 * One HTTP client for the whole session: avoids the cold-start race that killed
 * requests when the client closed too early, and keeps connections warm. Engine
 * Java (not CIO — CIO's chunked parser dies on long Gemini thinking responses).
 */
internal fun buildHttpClient(): HttpClient = HttpClient(Java) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
    }
}

/** One [McpToolClient] per configured MCP server command (not yet connected). */
internal fun buildMcpToolClients(chat: StartCommand.RunChat?): List<McpToolClient> =
    chat?.config?.mcpServers.orEmpty().map { cmd ->
        McpToolClient(cmd.split(Regex("\\s+")).filter { it.isNotEmpty() })
    }

/**
 * A [McpToolRouter] fanning the model's tool calls out to the server that owns
 * each tool (names unique across servers), or null when there are no servers.
 * Assumes each client is already connected.
 */
internal suspend fun buildToolRouter(
    mcpClients: List<McpToolClient>,
    chat: StartCommand.RunChat?,
): McpToolRouter? = mcpClients
    .zip(chat?.config?.mcpServers.orEmpty())
    .map { (client, cmd) ->
        val defs = client.listToolDefinitions()
        System.err.println("[mcp] $cmd → tools: ${defs.joinToString { it.name }}")
        McpToolRouter.Route(client, defs)
    }
    .takeIf { it.isNotEmpty() }
    ?.let(::McpToolRouter)

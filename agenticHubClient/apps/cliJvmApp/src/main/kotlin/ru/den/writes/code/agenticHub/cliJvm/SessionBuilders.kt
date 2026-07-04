package ru.den.writes.code.agenticHub.cliJvm

import org.koin.core.Koin
import org.koin.core.parameter.parametersOf
import ru.den.writes.code.agenticHub.features.llm.McpToolRouter
import ru.den.writes.code.agenticHub.features.mcpclient.McpToolClient
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand

/**
 * App-side wiring for a session: the MCP-client fan-out (the one bit that knows about
 * the [McpToolClient] platform impl). The shared HTTP client now lives in
 * platform:network (`networkModule`); the portable builders (agents/judges/session-id)
 * live in `AgentBuilders` in features:viewModel.
 */

/**
 * One [McpToolClient] per configured MCP server command (not yet connected),
 * each resolved from the graph (`mcpClientModule`) with the parsed command.
 */
internal fun buildMcpToolClients(koin: Koin, chat: StartCommand.RunChat?): List<McpToolClient> =
    chat?.config?.mcpServers.orEmpty().map { cmd ->
        koin.get<McpToolClient> { parametersOf(cmd.split(Regex("\\s+")).filter { it.isNotEmpty() }) }
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

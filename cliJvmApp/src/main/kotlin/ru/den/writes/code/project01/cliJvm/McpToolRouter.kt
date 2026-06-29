package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.shared.llm.ToolCall
import ru.den.writes.code.project01.shared.llm.ToolDefinition
import ru.den.writes.code.project01.shared.llm.ToolExecutor

/**
 * Fans the model's tool calls out across several MCP servers. Each [Route] pairs one
 * backing [ToolExecutor] (an [McpToolClient]) with the tools it advertises; the router
 * exposes the union as [toolDefs] and dispatches each [execute] to the server that owns
 * the named tool. Tool names must be unique across servers — a collision is a config
 * error, rejected fail-fast at construction so the model never sees an ambiguous catalog.
 *
 * Generic over [ToolExecutor] (not [McpToolClient]) so the routing logic is unit-testable
 * with fakes, without spawning a real server process.
 */
class McpToolRouter(routes: List<Route>) : ToolExecutor {

    /** One backing server: the [executor] that runs its tools and the [toolDefs] it offers. */
    data class Route(val executor: ToolExecutor, val toolDefs: List<ToolDefinition>)

    /** The combined catalog offered to the model — every server's tools, in order. */
    val toolDefs: List<ToolDefinition> = routes.flatMap { it.toolDefs }

    private val byName: Map<String, ToolExecutor> = buildMap {
        for (route in routes) {
            for (def in route.toolDefs) {
                require(def.name !in this) {
                    "MCP tool name collision: '${def.name}' is offered by more than one server"
                }
                put(def.name, route.executor)
            }
        }
    }

    override suspend fun execute(call: ToolCall): String {
        val executor = byName[call.name]
            ?: error("No MCP server offers the tool '${call.name}'")
        return executor.execute(call)
    }
}

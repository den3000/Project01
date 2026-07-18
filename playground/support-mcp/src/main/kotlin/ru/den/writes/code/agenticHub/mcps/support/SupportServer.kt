package ru.den.writes.code.agenticHub.mcps.support

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Runs the support MCP server over stdio for the users/tickets fixture rooted at
 * [dataRoot]. Exposes four read-only tools an assistant needs to answer a support
 * conversation: `list_tickets`, `get_ticket`, `search_tickets`, `get_user`. The active
 * ticket id is normally in the assistant's [Current Task] block — the profile is
 * expected to prompt a `get_ticket` on the first turn. stdout is the JSON-RPC channel;
 * every diagnostic goes to stderr so it can't corrupt the protocol stream. Blocks until
 * the client disconnects (stdin closes).
 */
suspend fun runSupportServer(dataRoot: String) {
    val repo = SupportRepo(FileLoader(dataRoot))

    val server = Server(
        serverInfo = Implementation(name = "support-mcp", version = "0.1.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    )

    server.addTool(
        name = "list_tickets",
        description = "List every support ticket in the fixture (id, status, priority, subject, customer). " +
            "Sorted most-recently-updated first.",
        inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
    ) { _ ->
        CallToolResult(content = listOf(TextContent(repo.listTickets())))
    }

    server.addTool(
        name = "get_ticket",
        description = "Return the full record for one ticket by id (subject, description, status, " +
            "priority, timestamps, customer, comments).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "id",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Ticket id, e.g. 'TICKET-4412'. Usually taken from the current-task block.")
                    },
                )
            },
            required = listOf("id"),
        ),
    ) { request ->
        val id = request.arguments?.get("id")?.jsonPrimitive?.content.orEmpty()
        CallToolResult(content = listOf(TextContent(repo.getTicket(id))))
    }

    server.addTool(
        name = "search_tickets",
        description = "Find tickets whose subject or description contains the query as a " +
            "case-insensitive substring. Returns the same summary shape as list_tickets.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "query",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Substring to match (case-insensitive) against subject and description.")
                    },
                )
            },
            required = listOf("query"),
        ),
    ) { request ->
        val query = request.arguments?.get("query")?.jsonPrimitive?.content.orEmpty()
        CallToolResult(content = listOf(TextContent(repo.searchTickets(query))))
    }

    server.addTool(
        name = "get_user",
        description = "Return the full record for one customer by id (name, email, tariff, product, since). " +
            "The customer id lives inside a ticket's Customer field.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "id",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "User id from the ticket's Customer field, e.g. 'USER-102'.")
                    },
                )
            },
            required = listOf("id"),
        ),
    ) { request ->
        val id = request.arguments?.get("id")?.jsonPrimitive?.content.orEmpty()
        CallToolResult(content = listOf(TextContent(repo.getUser(id))))
    }

    server.addTool(
        name = "find_user",
        description = "Look up registered users by name (case-insensitive substring). Use it to tell a " +
            "registered customer from a guest: an empty result means the person is not a registered user.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "name",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Full or partial customer name, e.g. 'Иван' or 'Петров'.")
                    },
                )
            },
            required = listOf("name"),
        ),
    ) { request ->
        val name = request.arguments?.get("name")?.jsonPrimitive?.content.orEmpty()
        CallToolResult(content = listOf(TextContent(repo.findUser(name))))
    }

    server.addTool(
        name = "list_user_tickets",
        description = "List all tickets belonging to one customer by their user id (id, status, subject). " +
            "Use it on a return visit to report the status of the customer's existing tickets.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "customerId",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Customer id, e.g. 'USER-102' (from find_user or a ticket's Customer field).")
                    },
                )
            },
            required = listOf("customerId"),
        ),
    ) { request ->
        val customerId = request.arguments?.get("customerId")?.jsonPrimitive?.content.orEmpty()
        CallToolResult(content = listOf(TextContent(repo.listUserTickets(customerId))))
    }

    System.err.println(
        "[support-mcp] support MCP server ready on stdio for data root '$dataRoot' " +
            "(tools: list_tickets, get_ticket, search_tickets, get_user, find_user, list_user_tickets)",
    )
    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered(),
    ) { /* defaults */ }
    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
}

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
 * [dataRoot]. Read tools (`find_user`, `get_user`, `list_user_tickets`, `search_tickets`,
 * `get_ticket`, `list_tickets`) plus escalation `create_ticket` are always available. The
 * developer-only mutator `set_ticket_status` is registered **only when [devMode]** — the
 * launch config, not a chat token, is the access gate. stdout is the JSON-RPC channel;
 * every diagnostic goes to stderr so it can't corrupt the protocol stream. Blocks until
 * the client disconnects (stdin closes).
 */
suspend fun runSupportServer(dataRoot: String, devMode: Boolean = false) {
    val repo = SupportRepo(FileStore(dataRoot))

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

    server.addTool(
        name = "create_ticket",
        description = "Escalate to the dev team: open a new ticket for a registered customer when the " +
            "problem can't be solved from the docs. Returns the new ticket id to quote back to the user.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "customerId",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Customer id the ticket is for, e.g. 'USER-102' (from find_user).")
                    },
                )
                put(
                    "subject",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Short one-line summary of the problem.")
                    },
                )
                put(
                    "description",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Full problem description, including what was already tried.")
                    },
                )
            },
            required = listOf("customerId", "subject", "description"),
        ),
    ) { request ->
        val customerId = request.arguments?.get("customerId")?.jsonPrimitive?.content.orEmpty()
        val subject = request.arguments?.get("subject")?.jsonPrimitive?.content.orEmpty()
        val description = request.arguments?.get("description")?.jsonPrimitive?.content.orEmpty()
        CallToolResult(content = listOf(TextContent(repo.createTicket(customerId, subject, description))))
    }

    if (devMode) {
        server.addTool(
            name = "set_ticket_status",
            description = "Developer-only: set a ticket's status and record the resolution. Status is one " +
                "of new, in_progress, resolved, wontfix. Always include a resolution (the solution, or why " +
                "it won't be fixed).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "ticketId",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Ticket id to update, e.g. 'TICKET-4412'.")
                        },
                    )
                    put(
                        "status",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "One of: new, in_progress, resolved, wontfix.")
                        },
                    )
                    put(
                        "resolution",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "The solution to give the user, or the reason it won't be fixed.")
                        },
                    )
                },
                required = listOf("ticketId", "status", "resolution"),
            ),
        ) { request ->
            val ticketId = request.arguments?.get("ticketId")?.jsonPrimitive?.content.orEmpty()
            val status = request.arguments?.get("status")?.jsonPrimitive?.content.orEmpty()
            val resolution = request.arguments?.get("resolution")?.jsonPrimitive?.content.orEmpty()
            CallToolResult(content = listOf(TextContent(repo.setTicketStatus(ticketId, status, resolution))))
        }
    }

    val toolList = buildList {
        addAll(listOf("list_tickets", "get_ticket", "search_tickets", "get_user", "find_user", "list_user_tickets", "create_ticket"))
        if (devMode) add("set_ticket_status")
    }.joinToString(", ")
    System.err.println(
        "[support-mcp] support MCP server ready on stdio for data root '$dataRoot'" +
            (if (devMode) " [dev]" else "") + " (tools: $toolList)",
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

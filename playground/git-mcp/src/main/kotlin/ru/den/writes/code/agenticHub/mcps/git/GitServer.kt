package ru.den.writes.code.agenticHub.mcps.git

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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Runs the git MCP server over stdio for the repository at [repoRoot]. Exposes three
 * read-only tools so an assistant can ground itself in the project's live VCS state:
 * `current_branch`, `list_files` (optionally under a subdir) and `diff` (working-tree
 * or staged). stdout is the JSON-RPC channel — every diagnostic goes to stderr so it
 * can't corrupt the protocol stream. Blocks until the client disconnects (stdin closes).
 */
suspend fun runGitServer(repoRoot: String) {
    val repo = GitRepo(repoRoot, ProcessCommandRunner())

    val server = Server(
        serverInfo = Implementation(name = "git-mcp", version = "0.1.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    )

    server.addTool(
        name = "current_branch",
        description = "Return the current git branch of the project repository (or a detached-HEAD notice).",
        inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
    ) { _ ->
        CallToolResult(content = listOf(TextContent(repo.currentBranch())))
    }

    server.addTool(
        name = "list_files",
        description = "List files tracked by git in the project repository, optionally under a subdirectory.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "subdir",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Optional path (relative to the repo root) to restrict the listing.")
                    },
                )
            },
            required = emptyList(),
        ),
    ) { request ->
        val subdir = request.arguments?.get("subdir")?.jsonPrimitive?.content
        CallToolResult(content = listOf(TextContent(repo.listFiles(subdir))))
    }

    server.addTool(
        name = "diff",
        description = "Show the git diff of the project repository. Pass staged=true for the staged (index) diff.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "staged",
                    buildJsonObject {
                        put("type", "boolean")
                        put("description", "Show the staged diff instead of the working-tree diff.")
                    },
                )
            },
            required = emptyList(),
        ),
    ) { request ->
        val staged = request.arguments?.get("staged")?.jsonPrimitive?.booleanOrNull ?: false
        CallToolResult(content = listOf(TextContent(repo.diff(staged))))
    }

    System.err.println(
        "[git-mcp] git MCP server ready on stdio for repo '$repoRoot' " +
            "(tools: current_branch, list_files, diff)",
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

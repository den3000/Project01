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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Runs the git MCP server over stdio for the repository at [repoRoot]. Exposes four
 * read-only tools so an assistant can ground itself in the project's live VCS state:
 * `current_branch`, `list_files` (optionally under a subdir), `changed_files` and `diff`
 * (a base…head range, or the working tree). [defaultBase] pre-sets the range's base — a
 * PR pipeline passes the base commit once, and the model then calls `diff`/`changed_files`
 * with no arguments. stdout is the JSON-RPC channel — every diagnostic goes to stderr so
 * it can't corrupt the protocol stream. Blocks until the client disconnects (stdin closes).
 */
/**
 * The optional `base`/`head` refs shared by `diff` and `changed_files`. Both default to
 * the server's configured range, so a well-behaved caller omits them entirely.
 */
private fun rangeProperties(): JsonObject = buildJsonObject {
    put(
        "base",
        buildJsonObject {
            put("type", "string")
            put("description", "Ref to compare against. Omit to use the review's configured base.")
        },
    )
    put(
        "head",
        buildJsonObject {
            put("type", "string")
            put("description", "Ref being reviewed. Omit for HEAD.")
        },
    )
}

suspend fun runGitServer(repoRoot: String, defaultBase: String? = null) {
    val repo = GitRepo(repoRoot, ProcessCommandRunner(), defaultBase)

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
        name = "changed_files",
        description = "List the files changed in the code under review, one path per line. " +
            "Call with no arguments — the review's base commit is already configured.",
        inputSchema = ToolSchema(properties = rangeProperties(), required = emptyList()),
    ) { request ->
        val base = request.arguments?.get("base")?.jsonPrimitive?.content
        val head = request.arguments?.get("head")?.jsonPrimitive?.content
        CallToolResult(content = listOf(TextContent(repo.changedFiles(base, head))))
    }

    server.addTool(
        name = "diff",
        description = "Show the diff of the code under review. Call with no arguments — the review's " +
            "base commit is already configured. Pass staged=true for the staged (index) diff instead.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                rangeProperties().forEach { (name, schema) -> put(name, schema) }
                put(
                    "staged",
                    buildJsonObject {
                        put("type", "boolean")
                        put("description", "Show the staged diff instead. Ignored when a base is in play.")
                    },
                )
            },
            required = emptyList(),
        ),
    ) { request ->
        val base = request.arguments?.get("base")?.jsonPrimitive?.content
        val head = request.arguments?.get("head")?.jsonPrimitive?.content
        val staged = request.arguments?.get("staged")?.jsonPrimitive?.booleanOrNull ?: false
        CallToolResult(content = listOf(TextContent(repo.diff(base, head, staged))))
    }

    System.err.println(
        "[git-mcp] git MCP server ready on stdio for repo '$repoRoot'" +
            (defaultBase?.let { " (base: $it)" } ?: "") +
            " (tools: current_branch, list_files, changed_files, diff)",
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

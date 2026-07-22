package ru.den.writes.code.agenticHub.mcps.projectfs

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
import kotlinx.serialization.json.put

/**
 * Runs the project-filesystem MCP server over stdio for the tree at [projectRoot]. Gives
 * an assistant the file access the rest of the toolbox lacks: RAG returns similar chunks
 * and git-mcp a VCS view, but neither opens an arbitrary file the model chose itself.
 *
 * Tool names carry a `_project_` infix so they can't collide with git-mcp's
 * `list_files` / `diff` / `changed_files` / `current_branch` — the host's router rejects
 * duplicate names at construction, which would take the whole session down at startup.
 *
 * This file is wiring only: the schema a model reads, and the call that answers it. What
 * the answer *is* lives in [ProjectFsTools], which needs no transport to test.
 *
 * stdout is the JSON-RPC channel; diagnostics go to stderr. Blocks until stdin closes.
 */
suspend fun runProjectFsServer(projectRoot: String, writeExtensions: Set<String> = emptySet()) {
    val paths = ProjectPaths(projectRoot, writeExtensions)
    val io = RealFileIo(projectRoot)
    val tools = ProjectFsTools(
        listing = ProjectListing(paths, io),
        reader = ProjectReader(paths, io),
        search = ProjectSearch(paths, io),
        writer = ProjectWriter(paths, io),
    )

    val server = Server(
        serverInfo = Implementation(name = "projectfs-mcp", version = "0.1.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    )

    server.addTool(
        name = "list_project_files",
        description = "List the project's files, one path per line with its line count, so you can " +
            "tell which files fit in a single read. Optionally narrow by subdirectory or extension. " +
            "Build output, .git and IDE state are never listed. Returns up to 300 paths by default.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("subdir", subdirProperty("Path relative to the project root to restrict the listing, e.g. \"server\"."))
                put("ext", extProperty("md\" or \"kt,kts"))
                put("limit", intProperty("Maximum paths to return (default and maximum 300)."))
            },
            required = emptyList(),
        ),
    ) { request ->
        CallToolResult(content = listOf(TextContent(tools.listProjectFiles(request.arguments))))
    }

    server.addTool(
        name = "read_project_file",
        description = "Read a file from the project as numbered lines, so you can cite path:line and " +
            "aim a later edit. Reads 200 lines from the start by default (400 max); when the file is " +
            "longer the result ends with the exact call that continues it.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("path", pathProperty("server/README.md"))
                put("offset", intProperty("1-based line to start at (default 1)."))
                put("limit", intProperty("How many lines to return (default 200, maximum 400)."))
            },
            required = listOf("path"),
        ),
    ) { request ->
        CallToolResult(content = listOf(TextContent(tools.readProjectFile(request.arguments))))
    }

    server.addTool(
        name = "search_project_files",
        description = "Search the project's files and return matching lines as path:line: text. " +
            "Matching is literal unless regex=true, so search one term at a time — a multi-word " +
            "query is looked for as one exact substring and almost always returns nothing. Start " +
            "wide questions with filesOnly=true — it returns just the files and hit counts, which " +
            "maps where to look for a fraction of the output. Up to 40 matches by default (100 max).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("query", stringProperty("A single term to look for, e.g. \"NetworkMonitor\"."))
                put("subdir", subdirProperty("Restrict the search to this directory."))
                put("ext", extProperty("kt\" or \"md"))
                put("regex", boolProperty("Treat query as a regular expression (default false)."))
                put("ignoreCase", boolProperty("Case-insensitive matching (default false)."))
                put("filesOnly", boolProperty("Return only file paths with hit counts, not the lines."))
                put("maxMatches", intProperty("Maximum matching lines to return (default 40, maximum 100)."))
            },
            required = listOf("query"),
        ),
    ) { request ->
        CallToolResult(content = listOf(TextContent(tools.searchProjectFiles(request.arguments))))
    }

    server.addTool(
        name = "write_project_file",
        description = "Create or overwrite a project file and return the unified diff of what changed. " +
            "Use it for files you author — a report, an ADR, a changelog. To edit an existing document " +
            "use replace_in_project_file instead: rewriting one whole costs its entire text twice.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("path", pathProperty("docs/report.md"))
                put("content", stringProperty("Full file contents to write."))
            },
            required = listOf("path", "content"),
        ),
    ) { request ->
        CallToolResult(content = listOf(TextContent(tools.writeProjectFile(request.arguments))))
    }

    server.addTool(
        name = "replace_in_project_file",
        description = "Replace an exact fragment in a project file and return the unified diff. This is " +
            "how you edit documentation. Pass 'old' verbatim as it appears in the file — line-number " +
            "gutters from read_project_file are stripped for you. Fails if 'old' is absent, and refuses " +
            "an ambiguous 'old' rather than guessing which occurrence you meant.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("path", pathProperty("README.md"))
                put("old", stringProperty("Exact text to find. Include neighbouring lines to disambiguate."))
                put("new", stringProperty("Replacement text. Empty string deletes the fragment."))
                put("replaceAll", boolProperty("Replace every occurrence instead of failing on ambiguity."))
            },
            required = listOf("path", "old", "new"),
        ),
    ) { request ->
        CallToolResult(content = listOf(TextContent(tools.replaceInProjectFile(request.arguments))))
    }

    System.err.println(
        "[projectfs-mcp] project filesystem MCP server ready on stdio for '$projectRoot' " +
            (if (writeExtensions.isEmpty()) "" else "(запись только ${writeExtensions.sorted()}) ") +
            "(tools: list_project_files, read_project_file, search_project_files, " +
            "write_project_file, replace_in_project_file)",
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

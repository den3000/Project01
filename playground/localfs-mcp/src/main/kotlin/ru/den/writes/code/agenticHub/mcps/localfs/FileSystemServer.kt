package ru.den.writes.code.agenticHub.mcps.localfs

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
 * Runs our local-filesystem MCP server over stdio. Exposes two tools that compose a
 * document and write it to disk: `append_to_document` (accumulate a line in memory) and
 * `save_document` (flush the document to a file under [documentsDir]). The document lives
 * in a single session-scoped [DocumentStore]. stdout is the JSON-RPC channel — every
 * diagnostic goes to stderr so it can't corrupt the protocol stream. Blocks until the
 * client disconnects (stdin closes).
 */
suspend fun runFileSystemServer() {
    val document = DocumentStore()

    val server = Server(
        serverInfo = Implementation(name = "localfs-mcp", version = "0.1.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    )

    server.addTool(
        name = "append_to_document",
        description = "Append a line of text to the in-memory document. Pass any text — e.g. a " +
            "weather string returned by another server's tool — to compose the document.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "text",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Text line to append to the document.")
                    },
                )
            },
            required = listOf("text"),
        ),
    ) { request ->
        val text = request.arguments?.get("text")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val result = if (text == null) {
            "Error: the 'text' argument is required."
        } else {
            "Appended to document (${document.add(text)} lines total)."
        }
        CallToolResult(content = listOf(TextContent(result)))
    }

    server.addTool(
        name = "save_document",
        description = "Write the accumulated document to a file under the documents dir and " +
            "return its path.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "filename",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Target file name, defaults to \"document.md\".")
                    },
                )
            },
            required = emptyList(),
        ),
    ) { request ->
        val filename = request.arguments?.get("filename")?.jsonPrimitive?.content
        val lines = document.snapshot()
        val file = documentFileFor(filename)
        saveDocument(file, document.render())
        val result = "Saved document (${lines.size} lines) to ${file.absolutePath}."
        CallToolResult(content = listOf(TextContent(result)))
    }

    System.err.println(
        "[localfs-mcp] filesystem MCP server ready on stdio " +
            "(tools: append_to_document, save_document)",
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

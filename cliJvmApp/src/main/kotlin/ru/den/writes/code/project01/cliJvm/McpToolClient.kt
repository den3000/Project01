package ru.den.writes.code.project01.cliJvm

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import ru.den.writes.code.project01.shared.llm.ToolCall
import ru.den.writes.code.project01.shared.llm.ToolDefinition
import ru.den.writes.code.project01.shared.llm.ToolExecutor

/**
 * Bridges the agent to an MCP server spawned as a subprocess over stdio:
 * [listToolDefinitions] hands the model the available tools and
 * [ToolExecutor.execute] runs one via the server's `callTool`. Each invocation
 * is logged to stderr (visible in a run, off the stdout reply channel). The
 * server's own stderr is forwarded; its stdout is the JSON-RPC stream.
 */
class McpToolClient(private val command: List<String>) : ToolExecutor {
    private var process: Process? = null
    private val client = Client(clientInfo = Implementation(name = "cliJvmApp", version = "0.1.0"))

    /** Spawn the server subprocess and complete the MCP handshake. */
    suspend fun connect() {
        val p = withContext(Dispatchers.IO) {
            ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        }
        process = p
        client.connect(
            StdioClientTransport(
                input = p.inputStream.asSource().buffered(),
                output = p.outputStream.asSink().buffered(),
            ),
        )
    }

    /** The server's tools as neutral [ToolDefinition]s the model can be offered. */
    suspend fun listToolDefinitions(): List<ToolDefinition> =
        client.listTools().tools.map { it.toToolDefinition() }

    override suspend fun execute(call: ToolCall): String {
        System.err.println("[tool] ${call.name}(${call.arguments})")
        val result = client.callTool(call.name, call.arguments.toArgMap())
        val text = result.content.filterIsInstance<TextContent>().mapNotNull { it.text }.joinToString("\n")
        System.err.println("[tool] → ${text.take(160)}")
        return text
    }

    /** Force-kill the server subprocess; the agent process exits afterwards. */
    fun close() {
        process?.destroyForcibly()
    }
}

/** MCP [Tool] → neutral [ToolDefinition], rebuilding its input schema as a JSON-Schema object. */
internal fun Tool.toToolDefinition(): ToolDefinition =
    ToolDefinition(name = name, description = description, parameters = inputSchema.toJsonSchema())

/** A [ToolSchema] as the `{type:"object", properties, required}` JSON-Schema the model expects. */
internal fun ToolSchema.toJsonSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", properties ?: JsonObject(emptyMap()))
    required?.takeIf { it.isNotEmpty() }?.let { req ->
        put("required", JsonArray(req.map { JsonPrimitive(it) }))
    }
}

/** Tool-call arguments → a plain `Map` for `callTool`, unwrapping JSON primitives. */
internal fun JsonObject.toArgMap(): Map<String, Any?> = mapValues { (_, v) -> v.unwrap() }

private fun JsonElement.unwrap(): Any? = when (this) {
    is JsonPrimitive -> when {
        isString -> content
        else -> booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    }
    else -> this
}

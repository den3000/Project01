package ru.den.writes.code.project01.shared.llm

import kotlinx.serialization.json.JsonObject

/**
 * A tool the model may call: its [name], a human-readable [description] the
 * model reads to decide when to use it, and a JSON-Schema [parameters] object
 * describing the typed inputs. Neutral — each [LlmApi] maps it to its
 * provider's tool / function-declaration wire shape.
 */
data class ToolDefinition(
    val name: String,
    val description: String?,
    val parameters: JsonObject,
)

/**
 * A tool invocation the model asked for: the [name] of a declared tool and the
 * [arguments] object it supplied (keys matching the tool's parameter schema).
 */
data class ToolCall(
    val name: String,
    val arguments: JsonObject,
)

/**
 * Executes a [ToolCall] and returns its textual result, which the caller feeds
 * back to the model so it can finish the turn. The agent core stays unaware of
 * how the tool is backed (MCP, a local function, …) — the host supplies the
 * implementation.
 */
fun interface ToolExecutor {
    suspend fun execute(call: ToolCall): String
}

package ru.den.writes.code.project01.mcpLab

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Runs our own MCP server over stdio. Exposes `current_weather` plus the scheduler tools
 * (`schedule_task` / `list_tasks` / `cancel_task` / `report`), all backed by the free
 * Open-Meteo API. While a client is connected, a background ticker fires due tasks every
 * [tickMs] and a reporter logs the aggregated summary every [summaryEveryMs]; both run on
 * `Dispatchers.IO` and are cancelled on disconnect. stdout is the JSON-RPC channel — every
 * diagnostic goes to stderr so it can't corrupt the protocol stream. Blocks until the
 * client disconnects (stdin closes).
 */
suspend fun runWeatherServer(
    tickMs: Long = 5_000,
    summaryEveryMs: Long = 60_000,
) {
    val http = HttpClient(Java) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    val weather = OpenMeteoClient(http)
    val engine = buildWeatherScheduler(defaultScheduleFile(), weather::currentWeather)
    // The pipeline's middle/final stages: accumulate text into a report, then save it.
    val report = ReportStore()

    val server = Server(
        serverInfo = Implementation(name = "mcpLab-weather", version = "0.1.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    )

    server.addTool(
        name = "current_weather",
        description = "Get the current weather for a city by name (e.g. \"Paris\").",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "city",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "City name to look up, e.g. \"Paris\" or \"Tokyo\".")
                    },
                )
            },
            required = listOf("city"),
        ),
    ) { request ->
        val city = request.arguments?.get("city")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val text = if (city == null) {
            "Error: the 'city' argument is required."
        } else {
            runCatching { weather.currentWeather(city) }
                .getOrElse { "Error fetching weather for \"$city\": ${it.message}" }
        }
        CallToolResult(content = listOf(TextContent(text)))
    }

    server.addTool(
        name = "schedule_task",
        description = "Schedule a weather lookup for a city: one-shot ('after_seconds') or " +
            "periodic ('every_seconds'). Provide exactly one, positive. Survives a restart.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "city",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "City to look up, e.g. \"Paris\".")
                    },
                )
                put(
                    "after_seconds",
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "Fire once, this many seconds from now.")
                    },
                )
                put(
                    "every_seconds",
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "Fire repeatedly, every this many seconds.")
                    },
                )
            },
            required = listOf("city"),
        ),
    ) { request ->
        val city = request.arguments?.get("city")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val after = request.arguments?.get("after_seconds")?.jsonPrimitive?.longOrNull
        val every = request.arguments?.get("every_seconds")?.jsonPrimitive?.longOrNull
        val schedule = scheduleFromArgs(after, every)
        val text = when {
            city == null -> "Error: the 'city' argument is required."
            schedule == null -> "Error: provide exactly one positive 'after_seconds' or 'every_seconds'."
            else -> "Scheduled ${renderTask(engine.add(city, schedule))}"
        }
        CallToolResult(content = listOf(TextContent(text)))
    }

    server.addTool(
        name = "list_tasks",
        description = "List all scheduled tasks (active, done, cancelled).",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
    ) { _ ->
        CallToolResult(content = listOf(TextContent(renderTasks(engine.list()))))
    }

    server.addTool(
        name = "cancel_task",
        description = "Cancel an active scheduled task by its id.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "id",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Task id from schedule_task / list_tasks.")
                    },
                )
            },
            required = listOf("id"),
        ),
    ) { request ->
        val id = request.arguments?.get("id")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val text = when {
            id == null -> "Error: the 'id' argument is required."
            engine.cancel(id) -> "Cancelled task $id."
            else -> "Error: no active task with id $id."
        }
        CallToolResult(content = listOf(TextContent(text)))
    }

    server.addTool(
        name = "report",
        description = "Return an aggregated report of the data collected by scheduled tasks so " +
            "far (count, time span, latest). Reads stored results only — calls no model.",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
    ) { _ ->
        CallToolResult(content = listOf(TextContent(engine.summary())))
    }

    server.addTool(
        name = "add_to_report",
        description = "Append a line of text to the in-memory weather report. Pass the weather " +
            "string returned by 'current_weather'. Step 2 of the report pipeline.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "text",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Text to add, e.g. a 'current_weather' result line.")
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
            "Added to report (${report.add(text)} entries total)."
        }
        CallToolResult(content = listOf(TextContent(result)))
    }

    server.addTool(
        name = "save_to_file",
        description = "Save the accumulated report to a file under the reports dir and return " +
            "its path. Step 3 of the report pipeline.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "filename",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Target file name, defaults to \"report.md\".")
                    },
                )
            },
            required = emptyList(),
        ),
    ) { request ->
        val filename = request.arguments?.get("filename")?.jsonPrimitive?.content
        val entries = report.snapshot()
        val file = reportFileFor(filename)
        saveReport(file, report.render())
        val result = "Saved report (${entries.size} entries) to ${file.absolutePath}."
        CallToolResult(content = listOf(TextContent(result)))
    }

    System.err.println(
        "[mcpLab] weather MCP server ready on stdio (tools: current_weather, schedule_task, " +
            "list_tasks, cancel_task, report, add_to_report, save_to_file)",
    )
    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered(),
    ) { /* defaults */ }
    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }

    // Background scheduler: tick due tasks every tickMs and log the aggregated summary
    // every summaryEveryMs, both on Dispatchers.IO. They live exactly as long as the
    // client connection — done.join() unblocks on disconnect, then both are cancelled.
    coroutineScope {
        val ticker = launch(Dispatchers.IO) { engine.runLoop(tickMs) }
        val reporter = launch(Dispatchers.IO) {
            while (isActive) {
                delay(summaryEveryMs)
                System.err.println("[mcpLab] ${engine.summary()}")
            }
        }
        done.join()
        ticker.cancel()
        reporter.cancel()
    }
    http.close()
}

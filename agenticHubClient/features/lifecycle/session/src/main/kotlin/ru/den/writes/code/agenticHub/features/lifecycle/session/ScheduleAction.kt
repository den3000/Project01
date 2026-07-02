package ru.den.writes.code.agenticHub.features.lifecycle.session

import kotlinx.serialization.json.JsonObject

/** What a scheduled task does when it fires. Resolved by task id at tick time. */
public sealed interface ScheduleAction {
    /** Call an MCP [tool] with [arguments] and store its text (collect). */
    public data class Collect(val tool: String, val arguments: JsonObject) : ScheduleAction

    /** Inject [prompt] as a turn (agent). */
    public data class Agent(val prompt: String) : ScheduleAction
}

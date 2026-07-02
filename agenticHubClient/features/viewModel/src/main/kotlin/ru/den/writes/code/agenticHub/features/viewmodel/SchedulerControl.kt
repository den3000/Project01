package ru.den.writes.code.agenticHub.features.viewmodel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import ru.den.writes.code.agenticHub.features.lifecycle.command.ScheduleSpec
import ru.den.writes.code.agenticHub.scheduling.Schedule
import ru.den.writes.code.agenticHub.scheduling.ScheduledTask
import ru.den.writes.code.agenticHub.scheduling.SchedulerEngine
import ru.den.writes.code.agenticHub.scheduling.TaskStatus

/**
 * Live control surface over a running [SchedulerEngine] for the REPL: add a task, filling the
 * id→[ScheduleAction] map the [CliTaskHandler] reads. Shared by startup `-schedule` (added
 * before the loop) and in-session `/schedule` (added while the loop runs — the engine's Mutex
 * serializes both). The map is the same instance the handler holds, so a REPL-added task is
 * routable as soon as it fires.
 */
public class SchedulerControl(
    private val engine: SchedulerEngine,
    private val actions: MutableMap<String, ScheduleAction>,
) {
    suspend fun add(spec: ScheduleSpec): ScheduledTask {
        val task = engine.add(spec.label(), scheduleOf(spec))
        actions[task.id] = spec.toAction()
        return task
    }

    /** Active tasks (status ACTIVE), for `/schedule` listing. */
    suspend fun listActive(): List<ScheduledTask> = engine.list().filter { it.status == TaskStatus.ACTIVE }

    /** Cancel one task by id; true iff a still-active task was cancelled. */
    suspend fun cancel(id: String): Boolean = engine.cancel(id)

    /** Cancel every active task — stops the schedule. Returns how many were cancelled. */
    suspend fun cancelAll(): Int = listActive().count { engine.cancel(it.id) }
}

/** Domain seconds → a scheduling [Schedule] (periodic `Every` vs one-shot `After`). */
public fun scheduleOf(spec: ScheduleSpec): Schedule {
    val ms = spec.seconds * 1000L
    return if (spec.periodic) Schedule.Every(ms) else Schedule.After(ms)
}

/** A human-readable label for listings (the engine carries it; not used for routing). */
public fun ScheduleSpec.label(): String = when (this) {
    is ScheduleSpec.Collect -> "collect $tool"
    is ScheduleSpec.Agent -> "agent: ${prompt.take(40)}"
}

/** The per-tick action a spec maps to — what [CliTaskHandler] runs when the task fires. */
public fun ScheduleSpec.toAction(): ScheduleAction = when (this) {
    is ScheduleSpec.Collect -> ScheduleAction.Collect(tool, parseToolArgs(args))
    is ScheduleSpec.Agent -> ScheduleAction.Agent(prompt)
}

private fun parseToolArgs(json: String?): JsonObject =
    json?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())

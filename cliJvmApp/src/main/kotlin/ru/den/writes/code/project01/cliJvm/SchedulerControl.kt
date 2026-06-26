package ru.den.writes.code.project01.cliJvm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import ru.den.writes.code.project01.cliJvm.command.ScheduleSpec
import ru.den.writes.code.project01.scheduling.Schedule
import ru.den.writes.code.project01.scheduling.ScheduledTask
import ru.den.writes.code.project01.scheduling.SchedulerEngine

/**
 * Live control surface over a running [SchedulerEngine] for the REPL: add a task, filling the
 * id→[ScheduleAction] map the [CliTaskHandler] reads. Shared by startup `-schedule` (added
 * before the loop) and in-session `/schedule` (added while the loop runs — the engine's Mutex
 * serializes both). The map is the same instance the handler holds, so a REPL-added task is
 * routable as soon as it fires.
 */
internal class SchedulerControl(
    private val engine: SchedulerEngine,
    private val actions: MutableMap<String, ScheduleAction>,
) {
    suspend fun add(spec: ScheduleSpec): ScheduledTask {
        val task = engine.add(spec.label(), scheduleOf(spec))
        actions[task.id] = spec.toAction()
        return task
    }
}

/** Domain seconds → a scheduling [Schedule] (periodic `Every` vs one-shot `After`). */
internal fun scheduleOf(spec: ScheduleSpec): Schedule {
    val ms = spec.seconds * 1000L
    return if (spec.periodic) Schedule.Every(ms) else Schedule.After(ms)
}

/** A human-readable label for listings (the engine carries it; not used for routing). */
internal fun ScheduleSpec.label(): String = when (this) {
    is ScheduleSpec.Collect -> "collect $tool"
    is ScheduleSpec.Agent -> "agent: ${prompt.take(40)}"
}

/** The per-tick action a spec maps to — what [CliTaskHandler] runs when the task fires. */
internal fun ScheduleSpec.toAction(): ScheduleAction = when (this) {
    is ScheduleSpec.Collect -> ScheduleAction.Collect(tool, parseToolArgs(args))
    is ScheduleSpec.Agent -> ScheduleAction.Agent(prompt)
}

private fun parseToolArgs(json: String?): JsonObject =
    json?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())

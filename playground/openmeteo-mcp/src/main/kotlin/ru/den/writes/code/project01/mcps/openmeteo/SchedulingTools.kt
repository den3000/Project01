package ru.den.writes.code.project01.mcps.openmeteo

import ru.den.writes.code.agenticHub.scheduling.JsonFileScheduleStore
import ru.den.writes.code.agenticHub.scheduling.Schedule
import ru.den.writes.code.agenticHub.scheduling.ScheduledTask
import ru.den.writes.code.agenticHub.scheduling.SchedulerEngine
import java.io.File

/** Default on-disk location for the server's schedule (tasks + collected results). */
fun defaultScheduleFile(): File =
    File(System.getProperty("user.home"), ".project01-mcplab/schedule.json")

/** Assemble the weather scheduler: JSON-persisted store + [WeatherTaskHandler] + system clock. */
fun buildWeatherScheduler(
    file: File,
    weatherFor: suspend (String) -> String,
    now: () -> Long = { System.currentTimeMillis() },
): SchedulerEngine =
    SchedulerEngine(
        store = JsonFileScheduleStore(file),
        handler = WeatherTaskHandler(weatherFor),
        now = now,
    )

/**
 * Build a [Schedule] from the `schedule_task` tool args: exactly one of [afterSeconds]
 * (one-shot) or [everySeconds] (periodic), and it must be positive. Anything else — both,
 * neither, or non-positive — is null, which the tool reports back as an error.
 */
fun scheduleFromArgs(afterSeconds: Long?, everySeconds: Long?): Schedule? = when {
    (afterSeconds == null) == (everySeconds == null) -> null
    afterSeconds != null -> afterSeconds.takeIf { it > 0 }?.let { Schedule.After(it * 1000) }
    else -> everySeconds!!.takeIf { it > 0 }?.let { Schedule.Every(it * 1000) }
}

/** One-line rendering of a schedule in seconds, e.g. `every 30s` / `after 60s`. */
internal fun renderSchedule(schedule: Schedule): String = when (schedule) {
    is Schedule.After -> "after ${schedule.delayMs / 1000}s"
    is Schedule.Every -> "every ${schedule.intervalMs / 1000}s"
}

/** One line per task: id, label, schedule, status, next firing (ms). */
fun renderTask(task: ScheduledTask): String =
    "${task.id}  ${task.label}  ${renderSchedule(task.schedule)}  ${task.status}  next@${task.nextRunAt}"

/** Multi-line listing for `list_tasks`, or a friendly line when there are none. */
fun renderTasks(tasks: List<ScheduledTask>): String =
    if (tasks.isEmpty()) "No scheduled tasks." else tasks.joinToString("\n", transform = ::renderTask)

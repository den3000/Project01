package ru.den.writes.code.project01.mcpLab

import ru.den.writes.code.project01.scheduling.ScheduledTask
import ru.den.writes.code.project01.scheduling.TaskHandler

/**
 * The per-tick payload for the weather scheduler: looks up the weather for the task's
 * [label][ScheduledTask.label] (a city, by convention) and returns the one-line summary
 * the engine stores as a result. [weatherFor] is injected so the tick stays offline —
 * production wires [OpenMeteoClient.currentWeather]; tests pass a fake.
 */
class WeatherTaskHandler(private val weatherFor: suspend (String) -> String) : TaskHandler {
    override suspend fun handle(task: ScheduledTask): String = weatherFor(task.label)
}

package ru.den.writes.code.project01.scheduling

import kotlinx.serialization.Serializable

/** A task's position in its lifecycle. No PAUSED in v1 — add it additively if needed. */
@Serializable
enum class TaskStatus { ACTIVE, DONE, CANCELLED }

/** One recorded firing: what the handler returned ([text]) and when ([producedAt], ms). */
@Serializable
data class TaskResult(
    val taskId: String,
    val producedAt: Long,
    val text: String,
)

/**
 * A scheduled task. [label] is a human-readable name (used in listings and summaries
 * and, by convention, as the handler's payload — e.g. a city). [schedule] says when,
 * [nextRunAt] caches the next firing moment (ms), [status] tracks the lifecycle. Pure
 * data — the behaviour lives in the [Schedule] extension functions and [SchedulerEngine].
 */
@Serializable
data class ScheduledTask(
    val id: String,
    val label: String,
    val schedule: Schedule,
    val nextRunAt: Long,
    val status: TaskStatus = TaskStatus.ACTIVE,
)

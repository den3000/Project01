package ru.den.writes.code.agenticHub.mcps.ticktick

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the TickTick Open API. Only the fields the tools use are mapped; the client
 * decodes with `ignoreUnknownKeys = true`, so unmapped fields are tolerated. Text fields are
 * defaulted so a response that omits one can't crash decoding.
 */
@Serializable
internal data class ProjectDto(
    val id: String,
    val name: String = "",
)

/** Response of `GET /open/v1/project/{id}/data` — the project's undone tasks (columns ignored). */
@Serializable
internal data class ProjectDataDto(val tasks: List<TaskDto> = emptyList())

/**
 * A TickTick task. `status` is 0 = normal (undone), 2 = completed. The `/data` endpoint only
 * returns undone tasks; a single-task fetch is what surfaces `status`/`completedTime` at review.
 */
@Serializable
internal data class TaskDto(
    val id: String,
    val projectId: String = "",
    val title: String = "",
    val status: Int = 0,
    val dueDate: String? = null,
    val startDate: String? = null,
    val completedTime: String? = null,
    val priority: Int? = null,
    val isAllDay: Boolean? = null,
)

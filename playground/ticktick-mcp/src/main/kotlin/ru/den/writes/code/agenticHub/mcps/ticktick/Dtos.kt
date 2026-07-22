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

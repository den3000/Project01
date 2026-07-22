package ru.den.writes.code.agenticHub.mcps.ticktick

/**
 * I/O port over the TickTick Open API — the impure edge of the server. Kept as a small interface
 * so [TicktickReports] (the logic) is unit-tested against an in-memory fake, with no network.
 * Production impl is [HttpTicktickApi]. Methods grow as tools are added.
 */
internal interface TicktickApi {
    /** All projects/lists for the authenticated user (`GET /open/v1/project`). */
    suspend fun projects(): List<ProjectDto>

    /** A project's **undone** tasks and columns (`GET /open/v1/project/{projectId}/data`). */
    suspend fun projectData(projectId: String): ProjectDataDto
}

package ru.den.writes.code.agenticHub.mcps.ticktick

/**
 * Surface over [TicktickApi] backing the MCP tools. Returns human-readable text — **facts only**,
 * no model call; the assistant does the reasoning. Kept free of I/O (all data comes through the
 * [api] port) so its logic is unit-tested against a fake.
 */
internal class TicktickReports(private val api: TicktickApi) {

    /** Every project, `id  name` per line; a clear notice when there are none. */
    suspend fun listProjects(): String {
        val projects = api.projects()
        if (projects.isEmpty()) return "(no projects)"
        return projects.joinToString("\n") { formatProject(it) }
    }
}

/** One project as `id  name`. The id is what task tools address a project by. */
internal fun formatProject(project: ProjectDto): String = "${project.id}  ${project.name}"

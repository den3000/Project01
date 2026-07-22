package ru.den.writes.code.agenticHub.mcps.ticktick

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TicktickReportsTest {

    //region list_projects
    @Test
    fun `when projects present - then listProjects returns id and name per line`() = runTest {
        // given
        val api = FakeTicktickApi(
            projects = listOf(
                ProjectDto(id = "p1", name = "Work"),
                ProjectDto(id = "p2", name = "Home"),
            ),
        )

        // when
        val actual = TicktickReports(api).listProjects()

        // then
        assertEquals("p1  Work\np2  Home", actual)
    }

    @Test
    fun `when no projects - then listProjects returns a clear notice`() = runTest {
        // given
        val api = FakeTicktickApi(projects = emptyList())

        // when
        val actual = TicktickReports(api).listProjects()

        // then
        assertEquals("(no projects)", actual)
    }
    //endregion
}

/** In-memory [TicktickApi]: returns scripted projects, no network. */
private class FakeTicktickApi(
    private val projects: List<ProjectDto> = emptyList(),
) : TicktickApi {
    override suspend fun projects(): List<ProjectDto> = projects
}

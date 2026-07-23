package ru.den.writes.code.agenticHub.mcps.ticktick

import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        val actual = TicktickReports(api, InMemorySnapshotStore()).listProjects()

        // then
        assertEquals("p1  Work\np2  Home", actual)
    }

    @Test
    fun `when no projects - then listProjects returns a clear notice`() = runTest {
        // given
        val api = FakeTicktickApi(projects = emptyList())

        // when
        val actual = TicktickReports(api, InMemorySnapshotStore()).listProjects()

        // then
        assertEquals("(no projects)", actual)
    }
    //endregion

    //region snapshot_week
    @Test
    fun `when tasks due in range across projects - then snapshotWeek stores only those and reports them`() = runTest {
        // given — only tasks whose dueDate is inside [from, to) are planned
        val api = FakeTicktickApi(
            projects = listOf(ProjectDto("p1", "Work"), ProjectDto("p2", "Home")),
            dataByProject = mapOf(
                "p1" to listOf(
                    task(id = "t1", title = "In range", dueDate = "2026-07-15T09:00:00.000+0000"),
                    task(id = "t2", title = "Too early", dueDate = "2026-07-01T09:00:00.000+0000"),
                    task(id = "t3", title = "No due date", dueDate = null),
                ),
                "p2" to listOf(
                    task(id = "t4", title = "Also in range", dueDate = "2026-07-19T23:00:00.000+0000"),
                ),
            ),
        )
        val store = InMemorySnapshotStore()

        // when
        val actual = TicktickReports(api, store).snapshotWeek(WEEK_FROM_MS, WEEK_TO_MS, "2026-W29")

        // then
        val snapshot = store.read("2026-W29")
        assertNotNull(snapshot)
        assertEquals(listOf("t1", "t4"), snapshot.planned.map { it.id })
        assertEquals("p1", snapshot.planned.first { it.id == "t1" }.projectId)
        assertTrue(actual.startsWith("Snapshot '2026-W29' saved: 2 planned task(s)."), actual)
    }

    @Test
    fun `when no tasks due in range - then snapshotWeek stores empty and reports none`() = runTest {
        // given
        val api = FakeTicktickApi(
            projects = listOf(ProjectDto("p1", "Work")),
            dataByProject = mapOf(
                "p1" to listOf(task(id = "t1", title = "Old", dueDate = "2026-01-01T09:00:00.000+0000")),
            ),
        )
        val store = InMemorySnapshotStore()

        // when
        val actual = TicktickReports(api, store).snapshotWeek(WEEK_FROM_MS, WEEK_TO_MS, "2026-W29")

        // then
        assertEquals(emptyList(), store.read("2026-W29")?.planned?.map { it.id })
        assertTrue("no planned tasks" in actual, actual)
    }
    //endregion

    //region review_week
    @Test
    fun `when planned tasks have mixed outcomes - then reviewWeek reports done not-done and gone`() = runTest {
        // given — snapshot of 3 planned; t1 completed, t2 still open, t3 vanished (404)
        val store = InMemorySnapshotStore()
        store.write(
            WeekSnapshot(
                label = "2026-W29",
                from = WEEK_FROM_MS,
                to = WEEK_TO_MS,
                planned = listOf(
                    PlannedTask(id = "t1", projectId = "p1", title = "Ship release"),
                    PlannedTask(id = "t2", projectId = "p1", title = "Write docs"),
                    PlannedTask(id = "t3", projectId = "p2", title = "Review PRs"),
                ),
            ),
        )
        val api = FakeTicktickApi(
            tasksById = mapOf(
                "t1" to task(id = "t1", title = "Ship release", status = 2),
                "t2" to task(id = "t2", title = "Write docs", status = 0),
                "t3" to null,
            ),
        )

        // when
        val actual = TicktickReports(api, store).reviewWeek("2026-W29")

        // then
        assertTrue(
            actual.startsWith("Week '2026-W29' review: 3 planned — 1 done, 1 not done, 1 gone."),
            actual,
        )
        assertTrue("Ship release (t1)" in actual, actual)
        assertTrue("Write docs (t2)" in actual, actual)
        assertTrue("Review PRs (t3)" in actual, actual)
    }

    @Test
    fun `when no snapshot for the label - then reviewWeek asks to snapshot first`() = runTest {
        // given
        val api = FakeTicktickApi()
        val store = InMemorySnapshotStore()

        // when
        val actual = TicktickReports(api, store).reviewWeek("2026-W29")

        // then
        assertTrue("No snapshot '2026-W29'" in actual, actual)
    }
    //endregion

    private companion object {
        val WEEK_FROM_MS: Long = Instant.parse("2026-07-13T00:00:00Z").toEpochMilli()
        val WEEK_TO_MS: Long = Instant.parse("2026-07-20T00:00:00Z").toEpochMilli()
    }
}

private fun task(
    id: String,
    title: String = "",
    dueDate: String? = null,
    status: Int = 0,
    completedTime: String? = null,
): TaskDto = TaskDto(id = id, title = title, dueDate = dueDate, status = status, completedTime = completedTime)

/** In-memory [TicktickApi]: scripted projects, per-project undone tasks, and per-id task fetches (null = 404). */
private class FakeTicktickApi(
    private val projects: List<ProjectDto> = emptyList(),
    private val dataByProject: Map<String, List<TaskDto>> = emptyMap(),
    private val tasksById: Map<String, TaskDto?> = emptyMap(),
) : TicktickApi {
    override suspend fun projects(): List<ProjectDto> = projects
    override suspend fun projectData(projectId: String): ProjectDataDto =
        ProjectDataDto(tasks = dataByProject[projectId] ?: emptyList())
    override suspend fun task(projectId: String, taskId: String): TaskDto? = tasksById[taskId]
}

/** In-memory [SnapshotStore]: label → snapshot map, no file I/O. */
private class InMemorySnapshotStore : SnapshotStore {
    private val byLabel = mutableMapOf<String, WeekSnapshot>()
    override fun read(label: String): WeekSnapshot? = byLabel[label]
    override fun write(snapshot: WeekSnapshot) { byLabel[snapshot.label] = snapshot }
}

package ru.den.writes.code.agenticHub.mcps.ticktick

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class WeekPlanTest {

    //region plannedMinutes
    @Test
    fun `when start and due span two hours - then plannedMinutes is 120`() {
        // when - then
        assertEquals(120L, plannedMinutes("2026-07-13T08:00:00.000+0000", "2026-07-13T10:00:00.000+0000"))
    }

    @Test
    fun `when a bound is missing - then plannedMinutes is 0`() {
        // when - then
        assertEquals(0L, plannedMinutes(null, "2026-07-13T10:00:00.000+0000"))
        assertEquals(0L, plannedMinutes("2026-07-13T10:00:00.000+0000", null))
    }

    @Test
    fun `when due is not after start - then plannedMinutes is 0`() {
        // when - then
        assertEquals(0L, plannedMinutes("2026-07-13T10:00:00.000+0000", "2026-07-13T10:00:00.000+0000"))
    }
    //endregion

    //region buildWeekPlan
    @Test
    fun `when tasks scheduled in range - then buildWeekPlan aggregates minutes and count per title`() {
        // given — Study 4h on two days, Guitar 2h once; a task outside the week is ignored
        val tasks = listOf(
            plannedTask("Study", "2026-07-13T08:00:00.000+0000", "2026-07-13T12:00:00.000+0000"),
            plannedTask("Study", "2026-07-14T08:00:00.000+0000", "2026-07-14T12:00:00.000+0000"),
            plannedTask("Guitar", "2026-07-13T17:00:00.000+0000", "2026-07-13T19:00:00.000+0000"),
            plannedTask("Old", "2026-07-01T08:00:00.000+0000", "2026-07-01T12:00:00.000+0000"),
        )

        // when
        val actual = buildWeekPlan(tasks, FROM_MS, TO_MS)

        // then
        assertEquals(listOf(PlanItem("Study", 480, 2), PlanItem("Guitar", 120, 1)), actual)
    }

    @Test
    fun `when no tasks fall in range - then buildWeekPlan is empty`() {
        // given
        val tasks = listOf(plannedTask("Old", "2026-07-01T08:00:00.000+0000", "2026-07-01T12:00:00.000+0000"))

        // when
        val actual = buildWeekPlan(tasks, FROM_MS, TO_MS)

        // then
        assertEquals(emptyList(), actual)
    }
    //endregion

    //region formatWeekPlan
    @Test
    fun `when plan has items - then formatWeekPlan lists them minutes-desc with a total`() {
        // given
        val items = listOf(PlanItem("Study", 1200, 5), PlanItem("Guitar", 360, 3))

        // when
        val actual = formatWeekPlan(items)

        // then
        assertEquals("Study — 20h 0m (×5)\nGuitar — 6h 0m (×3)\nTotal planned — 26h 0m", actual)
    }

    @Test
    fun `when plan is empty - then formatWeekPlan returns a notice`() {
        // when - then
        assertEquals("(no scheduled tasks in range)", formatWeekPlan(emptyList()))
    }
    //endregion

    private companion object {
        val FROM_MS: Long = Instant.parse("2026-07-13T00:00:00Z").toEpochMilli()
        val TO_MS: Long = Instant.parse("2026-07-20T00:00:00Z").toEpochMilli()
    }
}

private fun plannedTask(title: String, start: String, due: String): TaskDto =
    TaskDto(id = title, title = title, startDate = start, dueDate = due)

package ru.den.writes.code.agenticHub.mcps.ticktick

import kotlin.test.Test
import kotlin.test.assertEquals

class WeekReviewTest {

    //region classifyOutcome
    @Test
    fun `when task is null - then classifyOutcome is GONE`() {
        // when - then
        assertEquals(Outcome.GONE, classifyOutcome(null))
    }

    @Test
    fun `when task status is completed - then classifyOutcome is DONE`() {
        // when - then
        assertEquals(Outcome.DONE, classifyOutcome(TaskDto(id = "t1", status = 2)))
    }

    @Test
    fun `when task status is normal - then classifyOutcome is OPEN`() {
        // when - then
        assertEquals(Outcome.OPEN, classifyOutcome(TaskDto(id = "t1", status = 0)))
    }
    //endregion

    //region buildWeekReview
    @Test
    fun `when outcomes are mixed - then buildWeekReview groups them under a header`() {
        // given
        val snapshot = WeekSnapshot(label = "2026-W29", from = 0, to = 1, planned = emptyList())
        val outcomes = listOf(
            PlannedTask(id = "t1", projectId = "p1", title = "Ship release") to Outcome.DONE,
            PlannedTask(id = "t2", projectId = "p1", title = "Write docs") to Outcome.OPEN,
            PlannedTask(id = "t3", projectId = "p2", title = "Review PRs") to Outcome.GONE,
        )

        // when
        val actual = buildWeekReview(snapshot, outcomes)

        // then
        val expected = """
            Week '2026-W29' review: 3 planned — 1 done, 1 not done, 1 gone.

            Done:
            - Ship release (t1)

            Not done:
            - Write docs (t2)

            Gone (likely done):
            - Review PRs (t3)
        """.trimIndent()
        assertEquals(expected, actual)
    }

    @Test
    fun `when the plan was empty - then buildWeekReview returns a short notice`() {
        // given
        val snapshot = WeekSnapshot(label = "2026-W29", from = 0, to = 1, planned = emptyList())

        // when
        val actual = buildWeekReview(snapshot, emptyList())

        // then
        assertEquals("Week '2026-W29': the snapshot had no planned tasks.", actual)
    }
    //endregion
}

package ru.den.writes.code.project01.scheduling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleTest {

    @Test
    fun `when After delay - then nextRunAt is anchor plus delay`() {
        // given
        val schedule = Schedule.After(delayMs = 5_000)

        // when
        val actual = schedule.nextRunAt(anchorAt = 1_000)

        // then
        assertEquals(6_000, actual)
    }

    @Test
    fun `when Every interval - then nextRunAt is anchor plus interval`() {
        // given
        val schedule = Schedule.Every(intervalMs = 1_000)

        // when
        val actual = schedule.nextRunAt(anchorAt = 1_000)

        // then
        assertEquals(2_000, actual)
    }

    @Test
    fun `when now before nextRunAt - then isDue false`() {
        // given
        val task = task(nextRunAt = 100)

        // when - then
        assertFalse(task.isDue(now = 99))
    }

    @Test
    fun `when now equals nextRunAt - then isDue true`() {
        // given
        val task = task(nextRunAt = 100)

        // when - then
        assertTrue(task.isDue(now = 100))
    }

    @Test
    fun `when now past nextRunAt - then isDue true`() {
        // given
        val task = task(nextRunAt = 100)

        // when - then
        assertTrue(task.isDue(now = 150))
    }

    @Test
    fun `when status CANCELLED and now past nextRunAt - then isDue false`() {
        // given
        val task = task(nextRunAt = 100, status = TaskStatus.CANCELLED)

        // when - then
        assertFalse(task.isDue(now = 150))
    }

    @Test
    fun `when status DONE and now past nextRunAt - then isDue false`() {
        // given
        val task = task(nextRunAt = 100, status = TaskStatus.DONE)

        // when - then
        assertFalse(task.isDue(now = 150))
    }

    @Test
    fun `when After task advances - then status becomes DONE`() {
        // given
        val task = task(nextRunAt = 100, schedule = Schedule.After(delayMs = 50))

        // when
        val advanced = task.advance(now = 100)

        // then
        assertEquals(TaskStatus.DONE, advanced.status)
    }

    @Test
    fun `when Every task advances on time - then nextRunAt moves one interval forward`() {
        // given
        val task = task(nextRunAt = 100, schedule = Schedule.Every(intervalMs = 1_000))

        // when
        val advanced = task.advance(now = 100)

        // then
        assertEquals(1_100, advanced.nextRunAt)
    }

    @Test
    fun `when Every task advances - then status stays ACTIVE`() {
        // given
        val task = task(nextRunAt = 100, schedule = Schedule.Every(intervalMs = 1_000))

        // when
        val advanced = task.advance(now = 100)

        // then
        assertEquals(TaskStatus.ACTIVE, advanced.status)
    }

    @Test
    fun `when Every ticker overslept several intervals - then advance catches up past now`() {
        // given - nextRunAt 100, interval 100, now is 350 (2.5 intervals late)
        val task = task(nextRunAt = 100, schedule = Schedule.Every(intervalMs = 100))

        // when
        val advanced = task.advance(now = 350)

        // then - next occurrence on the 100-grid strictly after 350, missed ones skipped
        assertEquals(400, advanced.nextRunAt)
        assertTrue(advanced.nextRunAt > 350)
    }

    private fun task(
        nextRunAt: Long,
        status: TaskStatus = TaskStatus.ACTIVE,
        schedule: Schedule = Schedule.Every(intervalMs = 1_000),
    ) = ScheduledTask(
        id = "t1",
        label = "label",
        schedule = schedule,
        nextRunAt = nextRunAt,
        status = status,
    )
}

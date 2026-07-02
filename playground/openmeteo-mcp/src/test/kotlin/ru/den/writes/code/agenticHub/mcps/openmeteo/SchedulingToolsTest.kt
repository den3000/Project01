package ru.den.writes.code.agenticHub.mcps.openmeteo

import ru.den.writes.code.agenticHub.scheduling.Schedule
import ru.den.writes.code.agenticHub.scheduling.ScheduledTask
import ru.den.writes.code.agenticHub.scheduling.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SchedulingToolsTest {

    @Test
    fun `when exactly one positive arg - then After or Every in millis`() {
        // when - then
        assertEquals(Schedule.After(60_000L), scheduleFromArgs(afterSeconds = 60L, everySeconds = null))
        assertEquals(Schedule.Every(30_000L), scheduleFromArgs(afterSeconds = null, everySeconds = 30L))
    }

    @Test
    fun `when both or neither or non-positive - then null`() {
        // when - then
        assertNull(scheduleFromArgs(afterSeconds = null, everySeconds = null))
        assertNull(scheduleFromArgs(afterSeconds = 60L, everySeconds = 30L))
        assertNull(scheduleFromArgs(afterSeconds = 0L, everySeconds = null))
        assertNull(scheduleFromArgs(afterSeconds = null, everySeconds = -5L))
    }

    @Test
    fun `when render a task - then one line with id label schedule status next`() {
        // given
        val task = ScheduledTask(
            id = "abc", label = "Paris", schedule = Schedule.Every(30_000L),
            nextRunAt = 1000L, status = TaskStatus.ACTIVE,
        )

        // when - then
        assertEquals("abc  Paris  every 30s  ACTIVE  next@1000", renderTask(task))
    }

    @Test
    fun `when render an empty task list - then a friendly line`() {
        // when - then
        assertEquals("No scheduled tasks.", renderTasks(emptyList()))
    }

    @Test
    fun `when render several tasks - then one line each`() {
        // given
        val tasks = listOf(
            ScheduledTask("a", "Paris", Schedule.Every(30_000L), nextRunAt = 1000L),
            ScheduledTask("b", "Tokyo", Schedule.After(60_000L), nextRunAt = 2000L),
        )

        // when
        val actual = renderTasks(tasks)

        // then
        assertEquals(
            "a  Paris  every 30s  ACTIVE  next@1000\n" +
                "b  Tokyo  after 60s  ACTIVE  next@2000",
            actual,
        )
    }
}

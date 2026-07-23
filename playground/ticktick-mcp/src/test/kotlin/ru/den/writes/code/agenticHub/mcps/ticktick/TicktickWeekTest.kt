package ru.den.writes.code.agenticHub.mcps.ticktick

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TicktickWeekTest {

    //region isPlannedInRange
    @Test
    fun `when dueDate falls inside the window - then isPlannedInRange is true`() {
        // when - then
        assertEquals(true, isPlannedInRange("2026-07-15T09:00:00.000+0000", FROM_MS, TO_MS))
    }

    @Test
    fun `when dueDate is before the window - then isPlannedInRange is false`() {
        // when - then
        assertEquals(false, isPlannedInRange("2026-07-12T23:59:59.000+0000", FROM_MS, TO_MS))
    }

    @Test
    fun `when dueDate equals the inclusive lower bound - then isPlannedInRange is true`() {
        // when - then
        assertEquals(true, isPlannedInRange("2026-07-13T00:00:00.000+0000", FROM_MS, TO_MS))
    }

    @Test
    fun `when dueDate equals the exclusive upper bound - then isPlannedInRange is false`() {
        // when - then
        assertEquals(false, isPlannedInRange("2026-07-20T00:00:00.000+0000", FROM_MS, TO_MS))
    }

    @Test
    fun `when dueDate is null - then isPlannedInRange is false`() {
        // when - then
        assertEquals(false, isPlannedInRange(null, FROM_MS, TO_MS))
    }
    //endregion

    //region parseTicktickInstantMillis
    @Test
    fun `when date uses the TickTick offset without colon - then parseTicktickInstantMillis reads it`() {
        // when
        val actual = parseTicktickInstantMillis("2026-07-15T09:00:00.000+0000")

        // then
        assertEquals(Instant.parse("2026-07-15T09:00:00Z").toEpochMilli(), actual)
    }

    @Test
    fun `when date uses an ISO offset with colon - then parseTicktickInstantMillis reads it`() {
        // when
        val actual = parseTicktickInstantMillis("2026-07-15T12:00:00+03:00")

        // then
        assertEquals(Instant.parse("2026-07-15T09:00:00Z").toEpochMilli(), actual)
    }

    @Test
    fun `when string is blank or garbage - then parseTicktickInstantMillis is null`() {
        // when - then
        assertNull(parseTicktickInstantMillis("   "))
        assertNull(parseTicktickInstantMillis("not-a-date"))
    }
    //endregion

    //region formatSnapshot
    @Test
    fun `when snapshot has planned tasks - then formatSnapshot shows count and one line each`() {
        // given
        val snapshot = WeekSnapshot(
            label = "2026-W29",
            from = FROM_MS,
            to = TO_MS,
            planned = listOf(
                PlannedTask(id = "t1", projectId = "p1", title = "Ship release"),
                PlannedTask(id = "t2", projectId = "p1", title = "Write docs"),
            ),
        )

        // when
        val actual = formatSnapshot(snapshot)

        // then
        assertEquals(
            "Snapshot '2026-W29' saved: 2 planned task(s).\n- Ship release (t1)\n- Write docs (t2)",
            actual,
        )
    }

    @Test
    fun `when snapshot has no planned tasks - then formatSnapshot reports none`() {
        // given
        val snapshot = WeekSnapshot(label = "2026-W29", from = FROM_MS, to = TO_MS, planned = emptyList())

        // when
        val actual = formatSnapshot(snapshot)

        // then
        assertEquals("Snapshot '2026-W29': no planned tasks with a due date in range.", actual)
    }
    //endregion

    private companion object {
        val FROM_MS: Long = Instant.parse("2026-07-13T00:00:00Z").toEpochMilli()
        val TO_MS: Long = Instant.parse("2026-07-20T00:00:00Z").toEpochMilli()
    }
}

package ru.den.writes.code.agenticHub.mcps.atimelogger

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class AtimeloggerFormatTest {

    //region aggregateByActivity
    @Test
    fun `when intervals of same and different types - then aggregateByActivity sums per guid`() {
        // given
        val intervals = listOf(
            interval(guid = "w", from = 0, to = 3600),
            interval(guid = "w", from = 4000, to = 4000 + 1800),
            interval(guid = "s", from = 0, to = 3600),
        )

        // when
        val actual = aggregateByActivity(intervals, windowFromSec = 0, windowToSec = 100_000)

        // then
        assertEquals(mapOf("w" to 5400L, "s" to 3600L), actual)
    }

    @Test
    fun `when an interval straddles the window end - then aggregateByActivity counts only the in-window part`() {
        // given — 3600s span, but the window ends 1800s into it
        val intervals = listOf(interval(guid = "w", from = 1000, to = 1000 + 3600))

        // when
        val actual = aggregateByActivity(intervals, windowFromSec = 1000, windowToSec = 1000 + 1800)

        // then
        assertEquals(mapOf("w" to 1800L), actual)
    }

    @Test
    fun `when an interval lies fully outside the window - then aggregateByActivity drops it`() {
        // given
        val intervals = listOf(interval(guid = "w", from = 0, to = 100))

        // when
        val actual = aggregateByActivity(intervals, windowFromSec = 1000, windowToSec = 2000)

        // then
        assertEquals(emptyMap<String, Long>(), actual)
    }

    @Test
    fun `when no intervals - then aggregateByActivity is empty`() {
        // when
        val actual = aggregateByActivity(emptyList(), windowFromSec = 0, windowToSec = 1000)

        // then
        assertEquals(emptyMap<String, Long>(), actual)
    }
    //endregion

    //region formatDuration
    @Test
    fun `when formatDuration for various seconds - then hours and minutes rendered`() {
        // given
        val cases = mapOf(
            0L to "0m",
            90L to "1m",
            3600L to "1h 0m",
            3661L to "1h 1m",
            90_000L to "25h 0m",
        )

        // when - then
        cases.forEach { (seconds, expected) ->
            assertEquals(expected, formatDuration(seconds), "seconds=$seconds")
        }
    }
    //endregion

    //region formatTimeByActivity
    @Test
    fun `when totals present - then formatTimeByActivity lists names time-desc with a total`() {
        // given
        val byGuid = mapOf("w" to 7200L, "s" to 3600L)
        val nameByGuid = mapOf("w" to "Work", "s" to "Sleep")

        // when
        val actual = formatTimeByActivity(byGuid, nameByGuid)

        // then
        assertEquals("Work — 2h 0m\nSleep — 1h 0m\nTotal — 3h 0m", actual)
    }

    @Test
    fun `when a guid has no name - then formatTimeByActivity shows the guid`() {
        // given
        val byGuid = mapOf("unknown-guid" to 600L)
        val nameByGuid = emptyMap<String, String>()

        // when
        val actual = formatTimeByActivity(byGuid, nameByGuid)

        // then
        assertEquals("unknown-guid — 10m\nTotal — 10m", actual)
    }

    @Test
    fun `when totals empty - then formatTimeByActivity returns a clear notice`() {
        // when
        val actual = formatTimeByActivity(emptyMap(), emptyMap())

        // then
        assertEquals("(no tracked time in range)", actual)
    }
    //endregion

    //region localDateToEpochSeconds
    @Test
    fun `when localDateToEpochSeconds in UTC - then midnight of that date`() {
        // given
        val zone = ZoneId.of("UTC")

        // when
        val actual = localDateToEpochSeconds("2026-07-13", zone)

        // then
        assertEquals(Instant.parse("2026-07-13T00:00:00Z").epochSecond, actual)
    }

    @Test
    fun `when localDateToEpochSeconds in a plus-three zone - then three hours before UTC midnight`() {
        // given — Europe/Moscow is a fixed UTC+3 (no DST)
        val zone = ZoneId.of("Europe/Moscow")

        // when
        val actual = localDateToEpochSeconds("2026-07-13", zone)

        // then
        assertEquals(Instant.parse("2026-07-12T21:00:00Z").epochSecond, actual)
    }
    //endregion
}

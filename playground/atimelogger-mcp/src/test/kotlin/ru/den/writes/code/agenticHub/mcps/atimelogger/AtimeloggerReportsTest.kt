package ru.den.writes.code.agenticHub.mcps.atimelogger

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AtimeloggerReportsTest {

    //region list_activity_types
    @Test
    fun `when types present - then listActivityTypes returns one line per type`() = runTest {
        // given
        val api = FakeAtimeloggerApi(
            types = listOf(
                ActivityTypeDto(guid = "g1", name = "Work", color = "#ff0000"),
                ActivityTypeDto(guid = "g2", name = "Sleep", color = null),
            ),
        )

        // when
        val actual = AtimeloggerReports(api).listActivityTypes()

        // then
        val expected = "Work (#ff0000)\nSleep"
        assertEquals(expected, actual)
    }

    @Test
    fun `when no types - then listActivityTypes returns a clear notice`() = runTest {
        // given
        val api = FakeAtimeloggerApi(types = emptyList())

        // when
        val actual = AtimeloggerReports(api).listActivityTypes()

        // then
        assertEquals("(no activity types)", actual)
    }
    //endregion

    //region time_by_activity
    @Test
    fun `when intervals in range - then timeByActivity sums per type by name sorted desc with total`() = runTest {
        // given — Work 2h, Sleep 1h, window covers both fully
        val api = FakeAtimeloggerApi(
            types = listOf(
                ActivityTypeDto(guid = "w", name = "Work"),
                ActivityTypeDto(guid = "s", name = "Sleep"),
            ),
            intervals = listOf(
                interval(guid = "w", from = 1000, to = 1000 + 7200),
                interval(guid = "s", from = 1000, to = 1000 + 3600),
            ),
        )

        // when
        val actual = AtimeloggerReports(api).timeByActivity(fromSec = 0, toSec = 100_000)

        // then
        val expected = "Work — 2h 0m\nSleep — 1h 0m\nTotal — 3h 0m"
        assertEquals(expected, actual)
    }

    @Test
    fun `when no intervals - then timeByActivity returns a clear notice`() = runTest {
        // given
        val api = FakeAtimeloggerApi(types = listOf(ActivityTypeDto(guid = "w", name = "Work")))

        // when
        val actual = AtimeloggerReports(api).timeByActivity(fromSec = 0, toSec = 100_000)

        // then
        assertEquals("(no tracked time in range)", actual)
    }
    //endregion
}

/** In-memory [AtimeloggerApi]: returns scripted types/intervals, no network. */
private class FakeAtimeloggerApi(
    private val types: List<ActivityTypeDto> = emptyList(),
    private val intervals: List<IntervalDto> = emptyList(),
) : AtimeloggerApi {
    override suspend fun types(): List<ActivityTypeDto> = types
    override suspend fun intervals(fromSec: Long, toSec: Long): List<IntervalDto> = intervals
}

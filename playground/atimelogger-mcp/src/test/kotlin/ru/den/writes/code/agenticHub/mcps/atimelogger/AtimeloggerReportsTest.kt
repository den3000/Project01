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
}

/** In-memory [AtimeloggerApi]: returns scripted types, no network. */
private class FakeAtimeloggerApi(
    private val types: List<ActivityTypeDto> = emptyList(),
) : AtimeloggerApi {
    override suspend fun types(): List<ActivityTypeDto> = types
}

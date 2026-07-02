package ru.den.writes.code.project01.mcps.openmeteo

import kotlinx.coroutines.runBlocking
import ru.den.writes.code.agenticHub.scheduling.Schedule
import ru.den.writes.code.agenticHub.scheduling.ScheduledTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WeatherTaskHandlerTest {

    @Test
    fun `when handle - then looks up weather for the task label`() = runBlocking {
        // given
        var asked: String? = null
        val handler = WeatherTaskHandler { city ->
            asked = city
            "$city: clear sky, 20.0°C, wind 5.0 km/h"
        }

        // when
        val result = handler.handle(task(label = "Paris"))

        // then
        assertEquals("Paris", asked)
        assertEquals("Paris: clear sky, 20.0°C, wind 5.0 km/h", result)
    }

    @Test
    fun `when the weather source fails - then the error propagates`() {
        // given
        val handler = WeatherTaskHandler { throw IllegalStateException("network down") }

        // when - then
        assertFailsWith<IllegalStateException> {
            runBlocking { handler.handle(task(label = "Paris")) }
        }
    }

    private fun task(label: String): ScheduledTask =
        ScheduledTask(id = "t1", label = label, schedule = Schedule.Every(1_000L), nextRunAt = 0L)
}

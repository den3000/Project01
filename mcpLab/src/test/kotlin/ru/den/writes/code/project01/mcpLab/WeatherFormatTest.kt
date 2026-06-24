package ru.den.writes.code.project01.mcpLab

import kotlin.test.Test
import kotlin.test.assertEquals

class WeatherFormatTest {

    @Test
    fun `when formatWeather - then one-line place description temp wind summary`() {
        // given - when
        val actual = formatWeather("Paris, France", tempC = 14.0, windKmh = 9.0, weatherCode = 63)

        // then
        assertEquals("Paris, France: rain, 14.0°C, wind 9.0 km/h", actual)
    }

    @Test
    fun `when known weather codes - then mapped descriptions`() {
        // when - then
        assertEquals("clear sky", weatherCodeDescription(0))
        assertEquals("partly cloudy", weatherCodeDescription(2))
        assertEquals("thunderstorm", weatherCodeDescription(95))
    }

    @Test
    fun `when unknown weather code - then code fallback`() {
        // when - then
        assertEquals("code 7", weatherCodeDescription(7))
    }
}

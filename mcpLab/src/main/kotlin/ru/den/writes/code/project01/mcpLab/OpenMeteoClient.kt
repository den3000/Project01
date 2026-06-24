package ru.den.writes.code.project01.mcpLab

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Looks up current weather for a city via the free, key-less Open-Meteo API:
 * geocode the name → coordinates, then fetch the current conditions. Both
 * endpoints need no authentication.
 */
class OpenMeteoClient(private val http: HttpClient) {

    /** Returns a one-line weather summary for [city], or a friendly message if it can't. */
    suspend fun currentWeather(city: String): String {
        val geo = http.get(GEOCODING_URL) {
            parameter("name", city)
            parameter("count", 1)
        }.body<GeocodingResponse>()
        val place = geo.results?.firstOrNull() ?: return "No location found for \"$city\"."

        val forecast = http.get(FORECAST_URL) {
            parameter("latitude", place.latitude)
            parameter("longitude", place.longitude)
            parameter("current", "temperature_2m,wind_speed_10m,weather_code")
        }.body<ForecastResponse>()

        val label = listOfNotNull(place.name, place.country).joinToString(", ")
        val c = forecast.current
        return formatWeather(label, c.temperatureC, c.windKmh, c.weatherCode)
    }

    private companion object {
        const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    }
}

@Serializable
internal data class GeocodingResponse(val results: List<GeoResult>? = null)

@Serializable
internal data class GeoResult(
    val name: String,
    val country: String? = null,
    val latitude: Double,
    val longitude: Double,
)

@Serializable
internal data class ForecastResponse(val current: CurrentWeather)

@Serializable
internal data class CurrentWeather(
    @SerialName("temperature_2m") val temperatureC: Double,
    @SerialName("wind_speed_10m") val windKmh: Double,
    @SerialName("weather_code") val weatherCode: Int,
)

/** WMO weather-code → short human description; unknown codes fall back to `code N`. */
internal fun weatherCodeDescription(code: Int): String = when (code) {
    0 -> "clear sky"
    1, 2, 3 -> "partly cloudy"
    45, 48 -> "fog"
    51, 53, 55 -> "drizzle"
    61, 63, 65 -> "rain"
    66, 67 -> "freezing rain"
    71, 73, 75, 77 -> "snow"
    80, 81, 82 -> "rain showers"
    85, 86 -> "snow showers"
    95, 96, 99 -> "thunderstorm"
    else -> "code $code"
}

/** The one-line summary the tool returns, e.g. `Paris, France: rain, 14.0°C, wind 9.0 km/h`. */
internal fun formatWeather(place: String, tempC: Double, windKmh: Double, weatherCode: Int): String =
    "$place: ${weatherCodeDescription(weatherCode)}, $tempC°C, wind $windKmh km/h"

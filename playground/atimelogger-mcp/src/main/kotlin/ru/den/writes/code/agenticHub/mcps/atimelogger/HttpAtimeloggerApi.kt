package ru.den.writes.code.agenticHub.mcps.atimelogger

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter

/**
 * Production [AtimeloggerApi]: talks to the aTimeLogger v2 REST API over [http]. Authentication
 * (HTTP Basic) is installed once on the client's `defaultRequest` by the server, so this class
 * stays auth-agnostic and only shapes URLs and decodes responses.
 */
internal class HttpAtimeloggerApi(
    private val http: HttpClient,
    private val baseUrl: String = BASE_URL,
) : AtimeloggerApi {

    override suspend fun types(): List<ActivityTypeDto> =
        http.get("$baseUrl/types").body<TypesResponse>().types

    /**
     * One page of up to [INTERVALS_LIMIT] intervals for the window. A weekly range is far under
     * the cap; if the cap is ever hit the result may be truncated — that's flagged to stderr
     * rather than silently swallowed (no offset paging: the v2 API's paging is unverified, and a
     * single generous page is enough for the intended weekly use).
     */
    override suspend fun intervals(fromSec: Long, toSec: Long): List<IntervalDto> {
        val intervals = http.get("$baseUrl/intervals") {
            parameter("from", fromSec)
            parameter("to", toSec)
            parameter("limit", INTERVALS_LIMIT)
            parameter("order", "asc")
        }.body<IntervalsResponse>().intervals
        if (intervals.size >= INTERVALS_LIMIT) {
            System.err.println(
                "[atimelogger-mcp] interval page hit the $INTERVALS_LIMIT cap for [$fromSec, $toSec); " +
                    "result may be truncated — narrow the range",
            )
        }
        return intervals
    }

    private companion object {
        const val BASE_URL = "https://app.atimelogger.com/api/v2"
        const val INTERVALS_LIMIT = 2000
    }
}

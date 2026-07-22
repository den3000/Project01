package ru.den.writes.code.agenticHub.mcps.atimelogger

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

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

    private companion object {
        const val BASE_URL = "https://app.atimelogger.com/api/v2"
    }
}

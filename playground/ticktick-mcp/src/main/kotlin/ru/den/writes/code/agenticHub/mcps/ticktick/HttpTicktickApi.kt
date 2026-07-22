package ru.den.writes.code.agenticHub.mcps.ticktick

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

/**
 * Production [TicktickApi]: talks to the TickTick Open API over [http]. The OAuth2 Bearer token
 * is installed once on the client's `defaultRequest` by the server, so this class stays
 * auth-agnostic and only shapes URLs and decodes responses.
 */
internal class HttpTicktickApi(
    private val http: HttpClient,
    private val baseUrl: String = BASE_URL,
) : TicktickApi {

    override suspend fun projects(): List<ProjectDto> =
        http.get("$baseUrl/open/v1/project").body<List<ProjectDto>>()

    override suspend fun projectData(projectId: String): ProjectDataDto =
        http.get("$baseUrl/open/v1/project/$projectId/data").body<ProjectDataDto>()

    private companion object {
        const val BASE_URL = "https://api.ticktick.com"
    }
}

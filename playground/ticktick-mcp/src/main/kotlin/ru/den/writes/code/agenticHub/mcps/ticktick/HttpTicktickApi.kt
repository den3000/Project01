package ru.den.writes.code.agenticHub.mcps.ticktick

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode

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

    /**
     * The client keeps ktor's default `expectSuccess = false`, so a 404 comes back as a status
     * rather than an exception — the status is checked **before** decoding (decoding a 404 body as
     * a task would throw). 404 → null (gone); other non-OK → an error the tool surfaces as text.
     */
    override suspend fun task(projectId: String, taskId: String): TaskDto? {
        val response = http.get("$baseUrl/open/v1/project/$projectId/task/$taskId")
        return when (response.status) {
            HttpStatusCode.OK -> response.body<TaskDto>()
            HttpStatusCode.NotFound -> null
            else -> error("ticktick task $taskId: ${response.status}")
        }
    }

    private companion object {
        const val BASE_URL = "https://api.ticktick.com"
    }
}

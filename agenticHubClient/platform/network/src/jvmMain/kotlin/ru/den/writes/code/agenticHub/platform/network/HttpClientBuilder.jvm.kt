package ru.den.writes.code.agenticHub.platform.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Generous request timeout — LLM responses can take a while. */
internal const val REQUEST_TIMEOUT_MS = 300_000L

/**
 * One HTTP client for the whole session: avoids the cold-start race that killed
 * requests when the client closed too early, and keeps connections warm. Engine
 * Java (not CIO — CIO's chunked parser dies on long Gemini thinking responses).
 * `ContentNegotiation(Json)` so callers can `setBody(dto)` / `response.body<T>()`.
 */
internal fun buildHttpClient(): HttpClient = HttpClient(Java) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
    }
}

package ru.den.writes.code.agenticHub.features.rag.embedding

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import ru.den.writes.code.agenticHub.platform.logging.logWarn

private const val EMBED_PATH = "/api/embed"

@Serializable
internal data class OllamaEmbedRequest(val model: String, val input: List<String>)

@Serializable
internal data class OllamaEmbedResponse(val embeddings: List<List<Float>>)

/**
 * [Embedder] backed by a local Ollama server (`POST /api/embed`, batch). One HTTP
 * call embeds all [texts] at once; the returned list is positionally aligned with
 * the input. No API key — Ollama runs locally (swap [baseUrl] to reach a remote
 * VPS). The [httpClient] is injected (engine + `ContentNegotiation(Json)` come from
 * platform:network), so `setBody(dto)` / `body<T>()` just work and the class stays
 * portable — same pattern as the LLM provider APIs, minus credentials.
 *
 * The [Embedder] port has no error channel, so a non-2xx / transport failure is
 * logged and re-thrown; callers (IndexingPipeline / Retriever) propagate it. One
 * instance binds one [model].
 */
public class OllamaEmbedder(
    private val httpClient: HttpClient,
    private val model: String = "nomic-embed-text",
    private val baseUrl: String = "http://localhost:11434",
) : Embedder {
    override suspend fun embed(texts: List<String>): List<List<Float>> {
        if (texts.isEmpty()) return emptyList()

        val response = httpClient.post("$baseUrl$EMBED_PATH") {
            contentType(ContentType.Application.Json)
            setBody(OllamaEmbedRequest(model = model, input = texts))
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText().take(500)
            logWarn("[ollama] embed failed: ${response.status}: $body")
            error("Ollama embed failed: ${response.status}")
        }
        return response.body<OllamaEmbedResponse>().embeddings
    }
}

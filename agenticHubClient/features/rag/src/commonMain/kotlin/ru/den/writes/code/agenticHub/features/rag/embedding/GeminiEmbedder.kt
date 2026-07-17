package ru.den.writes.code.agenticHub.features.rag.embedding

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import ru.den.writes.code.agenticHub.platform.logging.logWarn

private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"

/** `batchEmbedContents` rejects (400) more than 100 requests per call — window at this. */
private const val MAX_BATCH = 100

@Serializable
internal data class GeminiEmbedPart(val text: String)

@Serializable
internal data class GeminiEmbedContent(val parts: List<GeminiEmbedPart>)

@Serializable
internal data class GeminiEmbedRequestItem(val model: String, val content: GeminiEmbedContent)

@Serializable
internal data class GeminiBatchEmbedRequest(val requests: List<GeminiEmbedRequestItem>)

@Serializable
internal data class GeminiEmbedding(val values: List<Float>)

@Serializable
internal data class GeminiBatchEmbedResponse(val embeddings: List<GeminiEmbedding>)

/**
 * [Embedder] backed by Gemini's embeddings endpoint
 * (`POST /v1beta/models/<model>:batchEmbedContents`, batched). [texts] are embedded in
 * windows of [MAX_BATCH] (the endpoint caps a batch at 100 requests) and the results
 * concatenated, so the returned list stays positionally aligned with the input. The
 * [apiKey] is a plain constructor argument (appended as `?key=`) — rag stays free of
 * any features:llm dependency; the credential is the caller's to supply. The
 * [httpClient] is injected (engine + `ContentNegotiation(Json)` from
 * platform:network), same pattern as [OllamaEmbedder].
 *
 * The [Embedder] port has no error channel, so a non-2xx is logged (status only — the
 * key rides in the URL, never in a logged string) and re-thrown; callers propagate it.
 * One instance binds one [model]; the cloud counterpart of the local [OllamaEmbedder].
 */
public class GeminiEmbedder(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String = "gemini-embedding-001",
) : Embedder {
    override suspend fun embed(texts: List<String>): List<List<Float>> {
        if (texts.isEmpty()) return emptyList()
        return texts.chunked(MAX_BATCH).flatMap { embedBatch(it) }
    }

    /** Embed a single ≤[MAX_BATCH] window in one `batchEmbedContents` call. */
    private suspend fun embedBatch(texts: List<String>): List<List<Float>> {
        val modelPath = "models/$model"
        val response = httpClient.post("$API_BASE/$modelPath:batchEmbedContents") {
            url { parameters.append("key", apiKey) }
            contentType(ContentType.Application.Json)
            setBody(
                GeminiBatchEmbedRequest(
                    requests = texts.map {
                        GeminiEmbedRequestItem(model = modelPath, content = GeminiEmbedContent(listOf(GeminiEmbedPart(it))))
                    },
                ),
            )
        }
        if (!response.status.isSuccess()) {
            // Status only — the API key is in the request URL; keep it out of logs.
            logWarn("[gemini] embed failed: ${response.status}")
            error("Gemini embed failed: ${response.status}")
        }
        return response.body<GeminiBatchEmbedResponse>().embeddings.map { it.values }
    }
}

package ru.den.writes.code.project01.cliJvm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.den.writes.code.project01.BuildKonfig
import kotlin.system.exitProcess

private const val KEY_TOKEN = "GEMINI_API_KEY"
private const val MODEL = "gemini-2.5-flash"
private const val ENDPOINT =
    "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
private const val DEFAULT_PROMPT =
    "Расскажи один интересный факт о языке Kotlin одним предложением."

@Serializable
private data class GeminiRequest(val contents: List<Content>)

@Serializable
private data class Content(val parts: List<Part>)

@Serializable
private data class Part(val text: String)

@Serializable
private data class GeminiResponse(val candidates: List<Candidate> = emptyList())

@Serializable
private data class Candidate(val content: Content? = null)

suspend fun main(args: Array<String>) {
    val (argKey, prompt) = parseArgs(args)
    val apiKey = argKey
        ?: BuildKonfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }
        ?: run {
            System.err.println(
                """$KEY_TOKEN not found.
                  |Pass "$KEY_TOKEN <key>" as program arguments,
                  |or add "$KEY_TOKEN=<key>" to local.properties at the project root,
                  |or set $KEY_TOKEN as an environment variable.""".trimMargin()
            )
            exitProcess(1)
        }
    println("prompt: $prompt")

    HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }.use { client ->
        val httpResponse = client.post(ENDPOINT) {
            url { parameters.append("key", apiKey) }
            contentType(ContentType.Application.Json)
            setBody(GeminiRequest(contents = listOf(Content(parts = listOf(Part(prompt))))))
        }

        if (!httpResponse.status.isSuccess()) {
            System.err.println("Gemini API error ${httpResponse.status}: ${httpResponse.bodyAsText()}")
            exitProcess(1)
        }

        val response: GeminiResponse = httpResponse.body()
        val text = response.candidates
            .firstOrNull()?.content?.parts
            ?.joinToString(separator = "") { it.text }
            ?.takeIf { it.isNotBlank() }

        println(text ?: "<empty response>")
    }
}

/** Returns the API key extracted from args (if any) and the remaining text as the prompt. */
private fun parseArgs(args: Array<String>): Pair<String?, String> {
    val joined = args.joinToString(" ").trim()
    val match = Regex("""$KEY_TOKEN\s+(\S+)""").find(joined)
        ?: return null to joined.ifEmpty { DEFAULT_PROMPT }
    val key = match.groupValues[1]
    val prompt = (joined.substring(0, match.range.first) +
            joined.substring(match.range.last + 1))
        .trim()
        .ifEmpty { DEFAULT_PROMPT }
    return key to prompt
}

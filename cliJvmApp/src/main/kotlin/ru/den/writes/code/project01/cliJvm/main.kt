package ru.den.writes.code.project01.cliJvm

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import ru.den.writes.code.project01.BuildKonfig
import kotlin.system.exitProcess

private const val KEY_TOKEN = "GEMINI_API_KEY"

/** Generous request timeout — LLM responses can take a while. */
private const val REQUEST_TIMEOUT_MS = 120_000L

suspend fun main(args: Array<String>) {
    val initialArgs = try {
        CliArgs.from(args)
    } catch (e: CliArgsException) {
        System.err.println(e.message)
        if (e is CliArgsException.MissingRequiredArgument) {
            System.err.println(CliArgs.USAGE)
        }
        exitProcess(1)
    }
    val apiKey = BuildKonfig.GEMINI_API_KEY.takeIf { it.isNotBlank() } ?: run {
        System.err.println(
            """$KEY_TOKEN not found.
              |Add "$KEY_TOKEN=<key>" to local.properties at the project root,
              |or set $KEY_TOKEN as an environment variable.""".trimMargin()
        )
        exitProcess(1)
    }

    // One client for the whole session: avoids the cold-start race that
    // sometimes killed requests when the client was closed too early, and
    // keeps connections warm between prompts.
    //
    // Engine: Java (JDK 11+ HttpClient). Picked over CIO because CIO's
    // chunked-encoding parser intermittently dies on long Gemini responses
    // ("Invalid chunk: content block of size N ended unexpectedly"), most
    // visibly with thinking-capable models like gemini-3.5-flash.
    HttpClient(Java) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                // Drop null fields from the request body so we send a clean
                // `generationConfig` only with the knobs the user set.
                explicitNulls = false
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }.use { client ->
        Agent(cliArgs = initialArgs, apiKey = apiKey, httpClient = client).runRepl()
    }
}

package ru.den.writes.code.project01.cliJvm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import ru.den.writes.code.project01.BuildKonfig
import kotlin.system.exitProcess

private const val KEY_TOKEN = "GEMINI_API_KEY"
private const val MODEL = "gemini-2.5-flash"
private const val ENDPOINT =
    "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

/** Generous request timeout — LLM responses can take a while. */
private const val REQUEST_TIMEOUT_MS = 120_000L

private const val QUIT_COMMAND = "/quit"
private const val EXIT_COMMAND = "/exit"
private const val PROMPT_INDICATOR = "> "

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
    HttpClient(CIO) {
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
        ask(client, apiKey, initialArgs)
        runRepl(client, apiKey, initialArgs)
    }
}

/**
 * Reads prompts from stdin and dispatches each as a new Gemini request.
 * Exits when the user types `/quit`, `/exit`, or sends EOF (Ctrl-D).
 * Other flags ([CliArgs.maxTokens], stop / end sequences) from the original
 * CLI invocation are preserved across iterations — only the prompt changes.
 */
private suspend fun runRepl(client: HttpClient, apiKey: String, baseArgs: CliArgs) {
    println()
    println("Type a new prompt and press Enter. Type $QUIT_COMMAND or $EXIT_COMMAND to leave.")
    while (true) {
        print(PROMPT_INDICATOR)
        System.out.flush()
        val line = readlnOrNull()?.trim() ?: break // EOF (Ctrl-D)
        if (line.isEmpty()) continue
        if (line.equals(QUIT_COMMAND, ignoreCase = true) ||
            line.equals(EXIT_COMMAND, ignoreCase = true)
        ) break
        ask(client, apiKey, baseArgs.copy(prompt = line))
    }
}

/**
 * Sends one request and prints the response (or the error). Swallows
 * exceptions so a single failure does not kill the REPL — the user can
 * retry with the next prompt.
 */
private suspend fun ask(client: HttpClient, apiKey: String, args: CliArgs) {
    println("prompt: ${args.prompt}")
    args.maxTokens?.let { println("maxTokens: $it") }
    args.stopSequences?.let { println("stopSequences: $it") }
    args.endSequence?.let { println("endSequence: $it") }

    try {
        val httpResponse = client.post(ENDPOINT) {
            url { parameters.append("key", apiKey) }
            contentType(ContentType.Application.Json)
            setBody(
                GeminiRequest(
                    contents = listOf(Content(parts = listOf(Part(args.prompt)))),
                    generationConfig = args.toGenerationConfig(),
                    systemInstruction = args.toSystemInstruction(),
                )
            )
        }

        if (!httpResponse.status.isSuccess()) {
            System.err.println("Gemini API error ${httpResponse.status}: ${httpResponse.bodyAsText()}")
            return
        }

        val response: GeminiResponse = httpResponse.body()
        val text = response.candidates
            .firstOrNull()?.content?.parts
            ?.joinToString(separator = "") { it.text }
            ?.takeIf { it.isNotBlank() }

        println(text ?: "<empty response>")
    } catch (e: Exception) {
        System.err.println("Request failed: ${e.message}")
    }
}

/**
 * Builds Gemini's `generationConfig` from the CLI flags. Only [CliArgs.stopSequences]
 * lands here — [CliArgs.endSequence] is a different concept (best-effort trailing
 * marker) and is handled via [toSystemInstruction]. Returns `null` when no relevant
 * flags are set so the field is omitted from the request body.
 */
private fun CliArgs.toGenerationConfig(): GenerationConfig? {
    if (maxTokens == null && stopSequences == null) return null
    return GenerationConfig(
        maxOutputTokens = maxTokens,
        stopSequences = stopSequences,
    )
}

/**
 * Translates [CliArgs.endSequence] into a `systemInstruction` asking the model
 * to terminate its response with that exact string. Gemini has no native
 * "force-end-with" field, so this is the closest API-level mechanism;
 * compliance is best-effort, not guaranteed.
 */
private fun CliArgs.toSystemInstruction(): SystemInstruction? =
    endSequence?.let { end ->
        SystemInstruction(
            parts = listOf(Part("Always end your response with the literal text: \"$end\""))
        )
    }

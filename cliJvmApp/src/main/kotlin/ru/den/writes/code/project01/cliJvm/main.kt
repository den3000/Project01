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
private const val REUSE_COMMAND = "/reuse"
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
        val response = ask(client, apiKey, initialArgs)
        response?.let {
            runRepl(client, apiKey, initialArgs.copy(prompt = it))
        } ?: run {
            runRepl(client, apiKey, initialArgs.copy(prompt = ""))
        }
    }
}

/**
 * Reads prompts from stdin and dispatches each as a new Gemini request.
 *
 * Callers seed [baseArgs] with the model's prior reply as the prompt (see
 * `main`), so the "base prompt" carried into this loop is whatever the model
 * just said. That's what `/reuse` resubmits.
 *
 * Recognised commands:
 * - `/quit`, `/exit` (or EOF / Ctrl-D) — leave the REPL.
 * - `/reuse` — feed the model's last reply back as a new prompt without
 *   retyping it. Handy for chain-of-thought style follow-ups where you want
 *   the model to keep building on what it just produced.
 *
 * Any other non-empty line is treated as a new prompt.
 *
 * Optional flags from the original CLI invocation ([CliArgs.maxTokens],
 * [CliArgs.stopSequences], [CliArgs.endSequence]) are preserved across
 * iterations — only the prompt changes between turns.
 */
private suspend fun runRepl(client: HttpClient, apiKey: String, baseArgs: CliArgs) {
    println()
    var lastResponse: String? = baseArgs.prompt
    while (true) {

        println("Type a new prompt and press Enter.\n"
                + "Type $QUIT_COMMAND or $EXIT_COMMAND to leave.\n"
                + "Type $REUSE_COMMAND to send prompt above."
        )
        print(PROMPT_INDICATOR)
        System.out.flush()
        val line = readlnOrNull()?.trim() ?: break // EOF (Ctrl-D)

        if (line.isEmpty()) continue

        if (line.equals(QUIT_COMMAND, ignoreCase = true) ||
            line.equals(EXIT_COMMAND, ignoreCase = true)
        ) break

        if (line.equals(REUSE_COMMAND, ignoreCase = true)
            && lastResponse?.isNotEmpty() == true
        ) {
            lastResponse = ask(client, apiKey, baseArgs)
            continue
        }

        lastResponse = ask(client, apiKey, baseArgs.copy(prompt = line))
    }
}

/**
 * Sends one request and prints the response (or the error). Swallows
 * exceptions so a single failure does not kill the REPL — the user can
 * retry with the next prompt.
 */
private suspend fun ask(client: HttpClient, apiKey: String, args: CliArgs): String? {
    println("========================================================================")
    println("prompt: ${args.prompt}")
    args.maxTokens?.let { println("maxTokens: $it") }
    args.stopSequences?.let { println("stopSequences: $it") }
    args.endSequence?.let { println("endSequence: $it") }
    args.temperature?.let { println("temperature: $it") }
    println("========================================================================")

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
            return null
        }

        val response: GeminiResponse = httpResponse.body()
        val text = response.candidates
            .firstOrNull()?.content?.parts
            ?.joinToString(separator = "") { it.text }
            ?.takeIf { it.isNotBlank() }

        println(text ?: "<empty response>")
        return text
    } catch (e: Exception) {
        System.err.println("Request failed: ${e.message}")
    }

    return null
}

/**
 * Builds Gemini's `generationConfig` from the CLI flags. Only [CliArgs.stopSequences]
 * lands here — [CliArgs.endSequence] is a different concept (best-effort trailing
 * marker) and is handled via [toSystemInstruction]. Returns `null` when no relevant
 * flags are set so the field is omitted from the request body.
 */
private fun CliArgs.toGenerationConfig(): GenerationConfig? {
    if (maxTokens == null && stopSequences == null && temperature == null) return null
    return GenerationConfig(
        maxOutputTokens = maxTokens,
        stopSequences = stopSequences,
        temperature = temperature,
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

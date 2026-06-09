package ru.den.writes.code.project01.cliJvm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
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
import kotlin.time.measureTimedValue

private const val KEY_TOKEN = "GEMINI_API_KEY"
/** Used when the caller didn't pass `-model`. */
private const val DEFAULT_MODEL = "gemini-2.5-flash"
private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
private fun endpointFor(model: String): String = "$API_BASE/$model:generateContent"

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
        runRepl(client, apiKey, initialArgs)
    }
}

/**
 * Drives a stdin-based REPL against Gemini, carrying a running conversation
 * history across turns.
 *
 * [baseArgs] is sent as the opening turn before the loop starts reading
 * user input — that's how the user's `-prompt` argument becomes the first
 * request. Its flags (model, maxTokens, stopSequences, temperature,
 * endSequence) stay the same across every iteration; only the prompt
 * changes between turns, built via `baseArgs.copy(prompt = ...)`.
 *
 * Multi-turn memory: Gemini's `generateContent` endpoint is stateless —
 * the server holds no session, so the client has to resend the whole
 * conversation each turn. We accumulate user/model turns into a local
 * history list after each successful exchange and ship the full list
 * with every subsequent request. Failed turns are not recorded — a user
 * turn without a model reply would leave the history with consecutive
 * `user` roles, which the API rejects. History is unbounded, so long
 * sessions inflate prompt tokens linearly.
 *
 * Recognised commands:
 * - `/quit`, `/exit` (or EOF / Ctrl-D) — leave the REPL.
 * - `/reuse` — resend the model's most recent reply as the next prompt,
 *   without retyping it. Handy for chain-of-thought style follow-ups where
 *   you want the model to keep building on what it just produced. No-op
 *   when there is no prior reply yet (e.g. the opening request failed or
 *   returned empty).
 *
 * Any other non-empty line is treated as a new prompt.
 */
private suspend fun runRepl(client: HttpClient, apiKey: String, baseArgs: CliArgs) {
    val history = mutableListOf<Content>()

    suspend fun send(prompt: String): String? {
        val response = ask(client, apiKey, baseArgs.copy(prompt = prompt), history)
        if (response != null) {
            history += Content(parts = listOf(Part(prompt)), role = "user")
            history += Content(parts = listOf(Part(response)), role = "model")
        }
        return response
    }

    var response: String? = send(baseArgs.prompt)
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

        if (line.equals(REUSE_COMMAND, ignoreCase = true)) {
            val prev = response?.takeIf { it.isNotEmpty() } ?: continue
            response = send(prev)
            continue
        }

        response = send(line)
    }
}

/**
 * Sends one request — the running [history] plus the current user turn
 * built from [args] — and prints the response (or the error). Swallows
 * exceptions so a single failure does not kill the REPL — the user can
 * retry with the next prompt.
 */
private suspend fun ask(
    client: HttpClient,
    apiKey: String,
    args: CliArgs,
    history: List<Content> = emptyList(),
): String? {
    val model = args.model ?: DEFAULT_MODEL
    println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
    println("prompt: ${args.prompt}")
    println("model: $model")
    args.maxTokens?.let { println("maxTokens: $it") }
    args.stopSequences?.let { println("stopSequences: $it") }
    args.endSequence?.let { println("endSequence: $it") }
    args.temperature?.let { println("temperature: $it") }
    println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")

    try {
        // measureTimedValue wraps the suspend call; the lambda inherits this
        // function's suspend context (it's inline), so we get wall-clock for
        // the actual HTTP round-trip including streaming of the body.
        val (httpResponse, duration) = measureTimedValue {
            client.post(endpointFor(model)) {
                url { parameters.append("key", apiKey) }
                contentType(ContentType.Application.Json)
                setBody(
                    GeminiRequest(
                        contents = history + Content(
                            parts = listOf(Part(args.prompt)),
                            role = "user",
                        ),
                        generationConfig = args.toGenerationConfig(),
                        systemInstruction = args.toSystemInstruction(),
                    )
                )
            }
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

        println("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
        println("duration: ${duration.inWholeMilliseconds} ms")
        response.usageMetadata?.let { u ->
            println(
                "tokens: prompt=${u.promptTokenCount ?: 0}" +
                    ", output=${u.candidatesTokenCount ?: 0}" +
                    (u.thoughtsTokenCount?.let { ", thoughts=$it" } ?: "") +
                    ", total=${u.totalTokenCount ?: 0}"
            )
        }
        println("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
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

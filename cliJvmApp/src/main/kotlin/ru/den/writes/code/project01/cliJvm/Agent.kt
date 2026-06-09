package ru.den.writes.code.project01.cliJvm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.measureTimedValue

/** Used when [CliArgs.model] is null. */
private const val DEFAULT_MODEL = "gemini-2.5-flash"
private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
private fun endpointFor(model: String): String = "$API_BASE/$model:generateContent"

private const val QUIT_COMMAND = "/quit"
private const val EXIT_COMMAND = "/exit"
private const val REUSE_COMMAND = "/reuse"
private const val PROMPT_INDICATOR = "> "

/**
 * One conversation with one Gemini model.
 *
 * Owns the running [history] and drives the REPL loop. Dependencies come
 * in through the constructor:
 * - [cliArgs] supplies the opening prompt and the request flags
 *   (model, maxTokens, stopSequences, temperature, endSequence) that
 *   stay the same across every turn.
 * - [apiKey] is the Gemini API key used for every request.
 * - [httpClient] is shared with the caller — the agent does not close it.
 */
internal class Agent(
    private val cliArgs: CliArgs,
    private val apiKey: String,
    private val httpClient: HttpClient,
) {
    private val history = mutableListOf<Content>()

    /**
     * Drives a stdin-based REPL against Gemini, carrying a running
     * conversation history across turns.
     *
     * Sends [cliArgs] as the opening turn before the loop starts reading
     * user input — that's how the user's `-prompt` argument becomes the
     * first request. Subsequent turns reuse the same flags via
     * `cliArgs.copy(prompt = ...)`; only the prompt changes between turns.
     *
     * Multi-turn memory: Gemini's `generateContent` endpoint is stateless —
     * the server holds no session, so the client has to resend the whole
     * conversation each turn. We accumulate user/model turns into [history]
     * after each successful exchange and ship the full list with every
     * subsequent request. Failed turns are not recorded — a user turn
     * without a model reply would leave the history with consecutive
     * `user` roles, which the API rejects. History is unbounded, so long
     * sessions inflate prompt tokens linearly.
     *
     * Recognised commands:
     * - `/quit`, `/exit` (or EOF / Ctrl-D) — leave the REPL.
     * - `/reuse` — resend the model's most recent reply as the next prompt,
     *   without retyping it. Handy for chain-of-thought style follow-ups
     *   where you want the model to keep building on what it just produced.
     *   No-op when there is no prior reply yet (e.g. the opening request
     *   failed or returned empty).
     *
     * Any other non-empty line is treated as a new prompt.
     */
    suspend fun runRepl() {
        var response: String? = send(cliArgs.prompt)
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
     * One turn: sends [prompt] paired with the running [history], records
     * both sides of the exchange on success.
     */
    private suspend fun send(prompt: String): String? {
        val response = ask(cliArgs.copy(prompt = prompt))
        if (response != null) {
            history += Content(parts = listOf(Part(prompt)), role = "user")
            history += Content(parts = listOf(Part(response)), role = "model")
        }
        return response
    }

    /**
     * Sends one HTTP request — the running [history] plus the current user
     * turn built from [args] — and prints the response (or the error).
     * Swallows exceptions so a single failure does not kill the REPL — the
     * user can retry with the next prompt.
     */
    private suspend fun ask(args: CliArgs): String? {
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
                httpClient.post(endpointFor(model)) {
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

            println(text ?: "<empty response>")
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
            return text
        } catch (e: Exception) {
            System.err.println("Request failed: ${e.message}")
        }

        return null
    }
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

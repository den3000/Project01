package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.cliJvm.db.HistoryStore

private const val QUIT_COMMAND = "/quit"
private const val EXIT_COMMAND = "/exit"
private const val REUSE_COMMAND = "/reuse"
private const val PROMPT_INDICATOR = "> "

/**
 * One running conversation.
 *
 * The agent is deliberately ignorant of which LLM is behind it: it only
 * knows the [LlmApi] surface. The concrete backend (e.g. [GeminiApi])
 * comes pre-configured through [llmApi] — model, credentials and transport
 * all live there.
 *
 * Persistence and the in-memory message list both live in [historyStore]:
 * it owns the file/DB choice, the session scoping, and the cache of
 * already-loaded turns. The agent just hydrates it on startup and asks it
 * for the current message view when building each request.
 *
 * What the agent does own:
 * - the opening [cliArgs] (carries the initial `-prompt` and generation
 *   knobs that stay the same across turns).
 */
internal class Agent(
    private val cliArgs: CliArgs,
    private val llmApi: LlmApi,
    private val historyStore: HistoryStore,
) {
    /**
     * Drives a stdin-based REPL, carrying a running conversation history
     * across turns.
     *
     * Startup sequence:
     *  1. Hydrate [historyStore] from disk. If anything came back, announce
     *     the restore (number of prior turns) so the user knows they're
     *     resuming.
     *  2. Send [cliArgs]'s `-prompt` as the next user turn. With restored
     *     history, this turn lands on top of the prior conversation —
     *     that's the "continue as if the agent never shut down" effect.
     *  3. Enter the REPL loop reading stdin.
     *
     * Generation knobs (`-maxTokens`, `-stopSequence`, `-endSequence`,
     * `-temperature`) stay the same on every turn; only the user-typed
     * text changes between iterations.
     *
     * Multi-turn memory: the chat API is stateless, so the client has to
     * resend the whole conversation each turn. We accumulate user / model
     * turns into [historyStore] after each successful exchange and ship
     * the full list with every subsequent request. Failed turns are not
     * recorded — a user turn without a model reply would leave the history
     * with two consecutive `USER` roles, which the API rejects. History
     * is unbounded, so long sessions inflate prompt tokens linearly.
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
        historyStore.load()
        if (historyStore.messages.isNotEmpty()) {
            // Every prior turn is a (user, model) pair, so size / 2 = turn count.
            System.err.println("[session] resumed (${historyStore.messages.size / 2} prior turn(s))")
        }

        // `main` only constructs an Agent in run mode, where prompt is
        // guaranteed non-null. List mode short-circuits before reaching here.
        var response: String? = send(cliArgs.prompt!!)
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
     * One turn: builds the request as «current history + [prompt] as a
     * user turn», asks [llmApi] for a reply, and on success records both
     * sides of the exchange in [historyStore] (which updates its own cache
     * along with the DB). Returns the model's reply text, or null if the
     * call failed (the implementation has already logged the error).
     */
    private suspend fun send(prompt: String): String? {
        val userTurn = Message(role = Role.USER, text = prompt)
        val response = llmApi.send(
            messages = historyStore.messages + userTurn,
            params = cliArgs.toGenerationParams(),
        )
        if (response != null) {
            historyStore.append(userTurn)
            historyStore.append(Message(role = Role.ASSISTANT, text = response))
        }
        return response
    }
}

/**
 * Lift the generation-related flags from the parsed CLI into the neutral
 * [GenerationParams] that crosses the [LlmApi] boundary. `-prompt` and
 * `-model` are not part of this — the former is the per-turn payload,
 * the latter is configured into the concrete [LlmApi] implementation.
 */
private fun CliArgs.toGenerationParams(): GenerationParams =
    GenerationParams(
        maxTokens = maxTokens,
        stopSequences = stopSequences,
        endSequence = endSequence,
        temperature = temperature,
    )

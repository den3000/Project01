package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.time.measureTimedValue

/**
 * One running conversation, in either REPL (Chat) or fire-and-forget
 * (OneShot) mode.
 *
 * The agent is deliberately ignorant of which LLM is behind it: it only
 * knows the [LlmApi] surface. The concrete backend (e.g. [GeminiApi])
 * comes pre-configured through [llmApi] — model, credentials and transport
 * all live there.
 *
 * Persistence, the in-memory message list, and running token / cost
 * totals all live in [historyStore] when it's non-null. When `null`
 * (OneShot), the agent runs without any history: nothing is loaded,
 * nothing is saved, and the per-turn footer only prints `turn:`
 * (no `session:` because there's no session to accumulate into).
 *
 * The choice between REPL and single-shot is keyed off the [cliArgs]
 * variant: [CliArgs.Chat] → REPL, [CliArgs.OneShot] → exit after the
 * opening turn.
 */
internal class Agent(
    private val cliArgs: CliArgs.PromptCommand,
    private val llmApi: LlmApi,
    private val historyStore: HistoryStore?,
    /**
     * Where the next user prompt comes from after the opening one.
     * Default reads from process `System.in` (REPL). Feed mode passes
     * a [ChunkedFilePromptSource]; tests pass scripted stubs. Only
     * consulted for Chat mode — OneShot exits before the loop.
     */
    private val promptSource: PromptSource =
        StdinPromptSource(BufferedReader(InputStreamReader(System.`in`))),
    /**
     * Optional follow-up source consulted after [promptSource] is
     * naturally exhausted. In production: when feed mode finishes
     * reading the file, the agent prints an interim session summary
     * and hands off to a stdin REPL via this source, so the user can
     * keep chatting (and see the final summary on `/exit`). Skipped
     * if [promptSource] aborted on error (e.g. context overflow) —
     * continuing into REPL with a broken conversation just produces
     * more errors.
     */
    private val replAfterFeed: PromptSource? = null,
) {
    /**
     * The source currently driving prompts. Set inside [send] so
     * `notifyTurnFailed` / `observeReply` hooks fire on the right
     * source as we transition from [promptSource] to [replAfterFeed].
     */
    private var currentSource: PromptSource = promptSource

    /**
     * Drives the conversation.
     *
     * Startup sequence:
     *  1. If [historyStore] is non-null, hydrate it from disk. Announce
     *     the restore (number of prior turns + accumulated tokens / cost)
     *     so the user knows they're resuming and what the meter shows.
     *  2. Send [cliArgs]'s `-prompt` as the next user turn. With restored
     *     history, this turn lands on top of the prior conversation —
     *     that's the "continue as if the agent never shut down" effect
     *     for Chat. OneShot starts with empty history so the prompt is
     *     the whole conversation.
     *  3. If [cliArgs] is [CliArgs.OneShot] — return. Otherwise loop on
     *     [promptSource]; the source decides when to stop (REPL: `/exit`
     *     or EOF; feed: file exhausted or prior turn failed).
     *
     * Generation knobs (`-maxTokens`, `-stopSequence`, `-endSequence`,
     * `-temperature`) stay the same on every turn; only the user-typed
     * text changes between iterations.
     *
     * Multi-turn memory (Chat): the chat API is stateless, so the client
     * has to resend the whole conversation each turn. We accumulate user /
     * model turns into [historyStore] after each successful exchange and
     * ship the full list with every subsequent request. Failed turns are
     * not recorded — a user turn without a model reply would leave the
     * history with two consecutive `USER` roles, which the API rejects.
     * History is unbounded, so long sessions inflate prompt tokens
     * linearly.
     */
    suspend fun run() {
        historyStore?.let { store ->
            store.load()
            if (store.messages.isNotEmpty()) {
                val s = store.stats
                System.err.println(
                    "[session] resumed: ${store.messages.size / 2} prior turn(s), " +
                        "tokens so far: total=${s.totalTokens}, cost=${formatCost(s.totalCostUsd, knownPricing = true)}"
                )
            }
        }

        send(cliArgs.prompt)

        // OneShot mode: single round-trip, no REPL.
        if (cliArgs is CliArgs.OneShot) {
            return
        }

        try {
            while (true) {
                val next = promptSource.nextPrompt() ?: break
                send(next)
            }

            // Phase 2: if the primary source ran out naturally (e.g. feed
            // file fully consumed, NOT aborted on error) and a follow-up
            // REPL source was supplied, announce an interim summary and
            // keep chatting from stdin until the user types /exit.
            if (replAfterFeed != null && !promptSource.terminated) {
                historyStore?.let { printSessionSummary(it.stats, label = "[feed done — interim summary]") }
                System.err.println("[continuing in REPL — type /exit or /quit to leave]")
                currentSource = replAfterFeed
                while (true) {
                    val next = replAfterFeed.nextPrompt() ?: break
                    send(next)
                }
            }
        } finally {
            historyStore?.let { printSessionSummary(it.stats) }
        }
    }

    /**
     * One turn: builds the request as «current history (if any) + [prompt]
     * as a user turn», asks [llmApi] for a reply.
     *
     * - On success: persists both sides of the exchange to [historyStore]
     *   (if present), updates the source for `/reuse`, and prints the
     *   per-turn + session footer.
     * - On failure: prints the error line, notifies the source so feed
     *   mode aborts, returns null.
     */
    private suspend fun send(prompt: String): String? {
        val userTurn = Message(role = Role.USER, text = prompt)
        val (result, duration) = measureTimedValue {
            llmApi.send(
                messages = (historyStore?.messages ?: emptyList()) + userTurn,
                params = cliArgs.toGenerationParams(),
            )
        }

        if (result.error != null) {
            System.err.println("[error] ${result.error}")
            currentSource.notifyTurnFailed()
            return null
        }

        val text = result.text
        if (text == null) {
            System.err.println("[error] empty response with no usage")
            currentSource.notifyTurnFailed()
            return null
        }

        val modelId = cliArgs.modelProvider.modelId
        historyStore?.append(userTurn)
        historyStore?.append(
            Message(role = Role.ASSISTANT, text = text),
            usage = result.usage,
            modelId = modelId,
        )
        currentSource.observeReply(text)

        println(text)
        printFooter(duration.inWholeMilliseconds, result.usage, modelId)
        return text
    }

    private fun printFooter(durationMs: Long, usage: Usage?, modelId: String) {
        val pricing = PricingRegistry.lookup(modelId)
        println("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
        println("duration: $durationMs ms")
        if (usage != null) {
            val cost = pricing?.let { PricingRegistry.cost(usage, it) }
            println("turn:    " + formatTurnTokens(usage) + "  cost=${formatCost(cost, pricing != null)}")
            historyStore?.let { store ->
                val s = store.stats
                val sessionCost = if (pricing != null) s.totalCostUsd else null
                println(
                    "session: turns=${s.turns} " + formatSessionTokens(s) +
                        "  cost=${formatCost(sessionCost, pricing != null)}"
                )
            }
        }
        println("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
    }

    private fun printSessionSummary(stats: SessionStats, label: String = "[session-summary]") {
        val pricing = PricingRegistry.lookup(cliArgs.modelProvider.modelId)
        // We tolerate cross-model sessions (-model changes between resumes):
        // the per-row cost is already baked into stats.totalCostUsd via
        // PricingRegistry lookups at append time. So we can always print
        // the numeric cost regardless of the current model's pricing —
        // it's just session totals.
        val costStr = if (stats.turns == 0) "$0.00" else "%.5f USD".format(stats.totalCostUsd)
        System.err.println(
            "$label turns=${stats.turns}  " +
                formatSessionTokens(stats) +
                "  cost=$costStr" +
                (if (pricing == null) "  (current model has no pricing entry)" else "")
        )
    }
}

private fun formatTurnTokens(usage: Usage): String = buildString {
    append("prompt=${usage.promptTokens}  output=${usage.outputTokens}")
    if (usage.thoughtsTokens > 0) append("  thoughts=${usage.thoughtsTokens}")
    append("  total=${usage.totalTokens}")
}

private fun formatSessionTokens(stats: SessionStats): String = buildString {
    append("prompt=${stats.totalPromptTokens}  output=${stats.totalOutputTokens}")
    if (stats.totalThoughtsTokens > 0) append("  thoughts=${stats.totalThoughtsTokens}")
    append("  total=${stats.totalTokens}")
}

private fun formatCost(usd: Double?, knownPricing: Boolean): String =
    when {
        !knownPricing -> "$? (no pricing)"
        usd == null -> "$? (no pricing)"
        else -> "$%.5f".format(usd)
    }

/**
 * Lift the generation-related flags from the parsed CLI into the neutral
 * [GenerationParams] that crosses the [LlmApi] boundary. `-prompt` and
 * `-model` are not part of this — the former is the per-turn payload,
 * the latter is configured into the concrete [LlmApi] implementation.
 *
 * Lives on the [CliArgs.PromptCommand] super-type so Chat and OneShot
 * share the same conversion.
 */
private fun CliArgs.PromptCommand.toGenerationParams(): GenerationParams =
    GenerationParams(
        maxTokens = maxTokens,
        stopSequences = stopSequences,
        endSequence = endSequence,
        temperature = temperature,
    )

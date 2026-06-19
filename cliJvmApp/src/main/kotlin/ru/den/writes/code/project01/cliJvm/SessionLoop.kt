package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.shared.llm.GenerationParams
import ru.den.writes.code.project01.shared.llm.LlmApi
import ru.den.writes.code.project01.shared.llm.Usage
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One running conversation, in either REPL (Chat) or fire-and-forget
 * (OneShot) mode.
 *
 * A thin façade over the MVI stack: it assembles the [TurnEngine] (the pure
 * turn engine), a [SessionViewModel] (which holds the loop), a [PlainView]
 * (which renders state to stdout/stderr), and the [IntentSource]s adapting
 * [promptSource] / [replAfterFeed], then runs them. The agent is deliberately
 * ignorant of which LLM is behind it — the concrete backend comes pre-
 * configured through [llmApi]. Persistence and running totals live in
 * [historyStore] when non-null (null = OneShot, no history).
 *
 * Kept as a stable `SessionLoop(...).run()` entry point while the Agent test
 * suite migrates onto [SessionViewModel] directly.
 */
internal class SessionLoop(
    private val cliArgs: CliArgs.PromptCommand,
    private val llmApi: LlmApi,
    private val historyStore: HistoryStore?,
    /**
     * Where prompts come from. Default reads process `System.in` (REPL); feed
     * mode passes a [ChunkedFilePromptSource]; tests pass scripted stubs.
     */
    private val promptSource: PromptSource =
        StdinPromptSource(BufferedReader(InputStreamReader(System.`in`))),
    /**
     * Optional follow-up source consulted after [promptSource] is exhausted —
     * the feed→REPL handoff (interim summary, then keep chatting on stdin).
     */
    private val replAfterFeed: PromptSource? = null,
    /** How history is shaped into each request. */
    private val strategy: ContextStrategy = ContextStrategy.FullHistory,
    /** Long-term + working memory façade, or null when `-memory-mode` is unset. */
    private val memory: MemoryProvider? = null,
    /** Per-stage agents; empty = single-agent mode (byte-identical to no routing). */
    private val routedAgents: List<RoutedAgent> = emptyList(),
) {
    private val commandRunner = CommandRunner(historyStore, memory, strategy)

    /**
     * Drive the conversation: hydrate + resume banners, the opening turn, the
     * REPL / feed loop, the feed→REPL handoff, and the final summary — all in
     * [SessionViewModel], rendered by [PlainView].
     */
    suspend fun run() {
        val engine = TurnEngine(cliArgs, llmApi, historyStore, strategy, memory, routedAgents)
        val viewModel = SessionViewModel(cliArgs, engine, commandRunner, historyStore, memory, strategy)
        val view = PlainView(multiAgent = routedAgents.isNotEmpty())
        // The 16s throttle only applies to a feed (which hands off to a REPL);
        // an interactive stdin / TUI source runs at full speed.
        val feedThrottle = if (replAfterFeed != null) 16.seconds else Duration.ZERO
        val primary = PromptSourceIntents(promptSource, feedThrottle)
        val followUp = replAfterFeed?.let { PromptSourceIntents(it) }
        view.run(viewModel, primary, followUp)
    }
}

/**
 * Display tag naming the agent that produced a reply, e.g.
 * `[[AGENT: interviewer:gemini-2.5-flash]]`. Emitted only in multi-agent
 * sessions; a null profile shows as `default`.
 */
internal fun agentTag(profileName: String?, modelId: String): String =
    "[[AGENT: ${profileName ?: "default"}:$modelId]]"

/**
 * Render the post-turn context-window fill line. Caller invokes this only when
 * the model's context window is known.
 *
 * Example: `(120000, 1000000) → "context: 120000 / 1000000 (12.0%)"`.
 */
internal fun formatContextFill(promptTokens: Int, windowTokens: Int): String {
    val pct = promptTokens.toDouble() / windowTokens * 100.0
    return "context: %d / %d (%.1f%%)".format(promptTokens, windowTokens, pct)
}

internal fun formatTurnTokens(usage: Usage): String = buildString {
    append("prompt=${usage.promptTokens}  output=${usage.outputTokens}")
    if (usage.thoughtsTokens > 0) append("  thoughts=${usage.thoughtsTokens}")
    append("  total=${usage.totalTokens}")
}

internal fun formatSessionTokens(stats: SessionStatsSnapshot): String = buildString {
    append("prompt=${stats.promptTokens}  output=${stats.outputTokens}")
    if (stats.thoughtsTokens > 0) append("  thoughts=${stats.thoughtsTokens}")
    append("  total=${stats.totalTokens}")
}

internal fun formatCost(usd: Double?, knownPricing: Boolean): String =
    when {
        !knownPricing -> "$? (no pricing)"
        usd == null -> "$? (no pricing)"
        else -> "$%.5f".format(usd)
    }

/**
 * Lift the generation-related flags from the parsed CLI into the neutral
 * [GenerationParams] that crosses the [LlmApi] boundary. `-prompt` (the
 * per-turn payload) and `-model` (configured into the concrete [LlmApi]) are
 * not part of this. Lives on the [CliArgs.PromptCommand] super-type so Chat and
 * OneShot share the same conversion.
 */
internal fun CliArgs.PromptCommand.toGenerationParams(): GenerationParams =
    GenerationParams(
        maxTokens = maxTokens,
        stopSequences = stopSequences,
        endSequence = endSequence,
        temperature = temperature,
    )

package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.delay
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.shared.agent.AgentConfig
import ru.den.writes.code.project01.shared.agent.AgentResponder
import ru.den.writes.code.project01.shared.llm.GenerationParams
import ru.den.writes.code.project01.shared.llm.LlmApi
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.llm.Usage
import ru.den.writes.code.project01.shared.memory.TaskBinding
import ru.den.writes.code.project01.shared.memory.TaskNotes
import ru.den.writes.code.project01.shared.memory.TaskStage
import ru.den.writes.code.project01.shared.memory.TaskStateMachine
import ru.den.writes.code.project01.shared.pricing.PricingRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.time.Duration.Companion.seconds
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
internal class SessionLoop(
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
    /**
     * How history is shaped into each request. Default
     * [ContextStrategy.FullHistory] sends the whole conversation every turn
     * (the un-compressed behaviour). Other strategies fold or trim the
     * history and may run a per-turn side effect (see [ContextStrategy.onTurn]).
     * Only Chat varies this; OneShot has no history, so it always uses
     * [ContextStrategy.FullHistory].
     */
    private val strategy: ContextStrategy = ContextStrategy.FullHistory,
    /**
     * Long-term + working memory façade. Non-null when the user
     * passed `-memory-mode preamble|system`; null otherwise (and for
     * OneShot, which has no memory by design). Read once per turn from
     * [send] and prepended to the wire list either as a USER/ASSISTANT
     * frame pair (PREAMBLE) or as one-or-more `Role.SYSTEM` messages
     * (SYSTEM). Memory entries are NEVER persisted into [historyStore].
     */
    private val memory: MemoryProvider? = null,
    /**
     * Per-stage agents: each owns a [TaskBinding] span and answers turns whose
     * active task stage falls in it. Empty (the default) = single-agent mode,
     * where [fallbackAgent] handles every turn — byte-identical to before
     * routing existed.
     */
    private val routedAgents: List<RoutedAgent> = emptyList(),
) {
    /**
     * The source currently driving prompts. Set inside [send] so
     * `notifyTurnFailed` / `observeReply` hooks fire on the right
     * source as we transition from [promptSource] to [replAfterFeed].
     */
    private var currentSource: PromptSource = promptSource

    /**
     * The default agent: built from this loop's model surface ([llmApi]) and
     * generation knobs, with no pinned profile. Answers every turn that no
     * [routedAgents] binding covers (and when there is no task / stage) — so an
     * empty [routedAgents] reproduces single-agent behaviour exactly.
     */
    private val fallbackAgent = RoutedAgent(
        binding = TaskBinding(TaskStage.CLARIFICATION, TaskStage.DONE),
        responder = AgentResponder(AgentConfig(llmApi = llmApi, params = cliArgs.toGenerationParams())),
        profileName = null,
        modelId = cliArgs.modelProvider.modelId,
    )

    /** Runs REPL `/`-commands; this loop prints the returned status lines to stderr. */
    private val commandRunner = CommandRunner(historyStore, memory, strategy)

    /**
     * The agent for [stage]: the first routed agent whose binding spans it,
     * else [fallbackAgent]. A null stage (no active task) always falls back.
     * Overlapping bindings resolve first-wins, in declaration order.
     */
    private fun agentFor(stage: TaskStage?): RoutedAgent =
        stage?.let { s -> routedAgents.firstOrNull { s in it.binding } } ?: fallbackAgent

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
            // Re-hydrate any per-(session, branch) strategy state (e.g. the
            // rolling summary + watermark) from persistence so a resumed
            // session keeps shaping context where it left off (overhead
            // totals were already re-seeded by load()).
            strategy.rebind(store)
        }

        // Working-memory resume: if a task is active and already has a stage,
        // announce where we're picking up. The state itself is re-injected
        // into every turn from disk, so the model continues without
        // re-explanation — this line is purely for the user.
        memory?.activeTaskId()?.let { id ->
            memory.store.loadTask(id)?.let { task ->
                task.stage?.let { stage ->
                    System.err.println(
                        "[task] resuming '$id' — stage ${stage.keyword}" +
                            (if (task.paused) " (paused)" else "") +
                            (task.goal?.let { ", goal: $it" } ?: "")
                    )
                }
            }
        }

        send(cliArgs.prompt)

        // OneShot mode: single round-trip, no REPL.
        if (cliArgs is CliArgs.OneShot) {
            return
        }

        try {
            drive(promptSource)

            // Phase 2: after the primary source winds down — for any reason
            // (file exhausted, REPL EOF, error abort) — hand off to the
            // follow-up source if one was supplied. We don't gate this on
            // `!terminated`: the user often wants to keep probing after the
            // first failure (e.g. push past a rate-limit or context-overflow
            // boundary manually). The label distinguishes a clean finish from
            // a stumble.
            if (replAfterFeed != null) {
                val label = if (promptSource.terminated) {
                    "[feed aborted — interim summary]"
                } else {
                    "[feed done — interim summary]"
                }
                historyStore?.let { printSessionSummary(it.stats, label = label) }
                System.err.println("[continuing in REPL — type /exit or /quit to leave]")
                drive(replAfterFeed)
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
        // The agent that answers this turn is picked by the active task's
        // stage; with no routed agents that's always the fallback (parity).
        val stage = memory?.activeTaskId()?.let { memory.store.loadTask(it)?.stage }
        val agent = agentFor(stage)
        val modelId = agent.modelId
        // Per-turn strategy side effect (rolling-summary compaction, facts
        // extraction, …) runs BEFORE the request is built and before this
        // turn's pair is appended: historyStore.messages is even-length here
        // (pairs are appended only on success), which the strategies'
        // role-alternation invariant relies on. No-op for FullHistory.
        // Compaction/facts run on the default model regardless of the routed
        // agent, so TurnContext keeps the default model id.
        historyStore?.let { strategy.onTurn(TurnContext(it, llmApi, prompt, cliArgs.modelProvider.modelId)) }
        val userTurn = Message(role = Role.USER, text = prompt)
        // planContext turns the stored history into the wire list — full
        // history, a summary pair + tail, a sliding window, etc., depending
        // on the strategy. OneShot (null store) sends just the prompt.
        val baseContext = historyStore?.let { strategy.planContext(it.messages) } ?: emptyList()
        // Memory layer (profile / rules / current task) sits ABOVE the
        // history tail so it stays stable across turns even as `baseContext`
        // gets re-shaped by the strategy. Empty list when no MemoryProvider
        // is wired in, or when every layer is empty — byte-identical to
        // the no-memory path.
        val memoryLayer = memory?.memoryLayerFor(agent.profileName) ?: emptyList()
        // Delegate the turn itself — wire assembly (memoryLayer + baseContext
        // + userTurn), the LLM call and stage-signal parsing — to the portable
        // responder. Timing wraps the whole call so the footer's duration is
        // unchanged.
        val (outcome, duration) = measureTimedValue {
            agent.responder.respond(baseContext = baseContext, memoryLayer = memoryLayer, userTurn = userTurn)
        }
        val result = outcome.result

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

        historyStore?.append(userTurn)
        historyStore?.append(
            Message(role = Role.ASSISTANT, text = text),
            usage = result.usage,
            modelId = modelId,
        )
        currentSource.observeReply(text)

        // Multi-agent session: tag each reply with the agent that produced it
        // (printed, never persisted) so per-stage routing is visible at a glance.
        if (routedAgents.isNotEmpty()) println(agentTag(agent.profileName, agent.modelId))
        println(text)
        printFooter(duration.inWholeMilliseconds, result.usage, modelId)
        // Auto-advance the active task's stage if the model signalled a move
        // in its reply (validated against the transition table). Runs after
        // the footer so the `[task]` line trails the turn block.
        maybeAdvanceTaskStage(outcome.proposedStage)
        delay(16.seconds)
        return text
    }

    /**
     * Pump [source] until it signals [PromptResult.Stop], sending prompts and
     * dispatching branch commands. Sets [currentSource] so the source's
     * `observeReply` / `notifyTurnFailed` hooks fire on the right source as we
     * move from a feed to the follow-up REPL.
     */
    private suspend fun drive(source: PromptSource) {
        currentSource = source
        while (true) {
            when (val next = source.nextPrompt()) {
                PromptResult.Stop -> return
                is PromptResult.Prompt -> send(next.text)
                is PromptResult.Command -> handleBranchCommand(next.command)
            }
        }
    }

    /**
     * Execute a REPL branch- / memory-management command by delegating to
     * [commandRunner] and printing each returned status line to stderr.
     */
    private suspend fun handleBranchCommand(command: BranchCommand) {
        commandRunner.run(command).forEach { System.err.println(it) }
    }

    /**
     * Auto-advance the active task's stage given the model's [proposed] move
     * (already parsed from the reply by [AgentResponder]; null when the reply
     * carried no valid `[[stage:<next>]]` marker). We honour it only when a
     * task is active, it isn't paused (pause means "hold here"), and the move
     * is allowed by [TaskStateMachine]. Illegal proposals are reported and
     * ignored — the next turn's injected `Allowed next:` re-teaches the model.
     * No-op without a MemoryProvider (OneShot / no `-memory-mode`) or active
     * task.
     */
    private fun maybeAdvanceTaskStage(proposed: TaskStage?) {
        val mem = memory ?: return
        val id = mem.activeTaskId() ?: return
        if (proposed == null) return
        val task = mem.store.loadTask(id) ?: TaskNotes(id, stage = TaskStage.INITIAL)
        if (task.paused) return
        val from = task.stage
        if (proposed == from) return
        if (!TaskStateMachine.canTransition(from, proposed)) {
            val allowed = from?.let(TaskStateMachine::allowedNext).orEmpty()
            System.err.println(
                "[task] model proposed ${from?.keyword ?: "(none)"} → ${proposed.keyword}, " +
                    "not allowed (allowed: ${allowed.joinToString(", ") { it.keyword }}) — ignored"
            )
            return
        }
        mem.store.saveTask(task.copy(stage = proposed))
        System.err.println("[task] stage: ${from?.keyword ?: "(none)"} → ${proposed.keyword} (auto)")
    }

    private fun printFooter(durationMs: Long, usage: Usage?, modelId: String) {
        val pricing = PricingRegistry.lookup(modelId)
        println("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
        println("duration: $durationMs ms")
        if (usage != null) {
            val cost = pricing?.let { PricingRegistry.cost(usage, it) }
            println("turn:    " + formatTurnTokens(usage) + "  cost=${formatCost(cost, pricing != null)}")
            // Context-fill line only when we know the window. Unknown
            // windows just skip the line — adding a "?" placeholder
            // would be noise on the meta-router / out-of-registry models.
            pricing?.contextWindowTokens?.let { window ->
                println(formatContextFill(usage.promptTokens, window))
            }
            historyStore?.let { store ->
                val s = store.stats
                val sessionCost = if (pricing != null) s.totalCostUsd else null
                println(
                    "session: turns=${s.turns} " + formatSessionTokens(s.snapshot()) +
                        "  cost=${formatCost(sessionCost, pricing != null)}"
                )
            }
        }
        println("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
        // Warn loudly when we're closing in on the limit. Done outside
        // the structured block so it stands out, and on stderr so it
        // survives stdout redirection during demos.
        val window = pricing?.contextWindowTokens
        if (usage != null && window != null) {
            val pct = usage.promptTokens.toDouble() / window * 100.0
            if (pct >= CONTEXT_WARN_PCT) {
                System.err.println(
                    "[warning] context window %.1f%% full — next turn may overflow".format(pct)
                )
            }
        }
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
                formatSessionTokens(stats.snapshot()) +
                "  cost=$costStr" +
                (if (pricing == null) "  (current model has no pricing entry)" else "")
        )
    }
}

/**
 * Threshold (% of context window) above which [SessionLoop.printFooter] emits
 * a `[warning] context window …% full` line to stderr.
 */
private const val CONTEXT_WARN_PCT: Double = 90.0

/**
 * Display tag naming the agent that produced a reply, e.g.
 * `[[AGENT: interviewer:gemini-2.5-flash]]`. [SessionLoop] emits it only in
 * multi-agent sessions; a null profile shows as `default`.
 */
internal fun agentTag(profileName: String?, modelId: String): String =
    "[[AGENT: ${profileName ?: "default"}:$modelId]]"

/**
 * Render the post-turn context-window fill line. Caller is responsible
 * for only invoking this when the model's context window is known —
 * the function itself doesn't second-guess that.
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
 * [GenerationParams] that crosses the [LlmApi] boundary. `-prompt` and
 * `-model` are not part of this — the former is the per-turn payload,
 * the latter is configured into the concrete [LlmApi] implementation.
 *
 * Lives on the [CliArgs.PromptCommand] super-type so Chat and OneShot
 * share the same conversion.
 */
internal fun CliArgs.PromptCommand.toGenerationParams(): GenerationParams =
    GenerationParams(
        maxTokens = maxTokens,
        stopSequences = stopSequences,
        endSequence = endSequence,
        temperature = temperature,
    )

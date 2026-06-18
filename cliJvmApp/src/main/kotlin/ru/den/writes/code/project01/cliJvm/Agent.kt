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
import ru.den.writes.code.project01.shared.memory.ProfileSection
import ru.den.writes.code.project01.shared.memory.TaskNotes
import ru.den.writes.code.project01.shared.memory.TaskStage
import ru.den.writes.code.project01.shared.memory.TaskStateMachine
import ru.den.writes.code.project01.shared.memory.isValidProfileName
import ru.den.writes.code.project01.shared.pricing.PricingRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/** Valid branch names: same shape as session names — alphanumerics, '_' or '-'. */
private val BRANCH_NAME_REGEX = Regex("^[a-zA-Z0-9_-]+$")

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
) {
    /**
     * The source currently driving prompts. Set inside [send] so
     * `notifyTurnFailed` / `observeReply` hooks fire on the right
     * source as we transition from [promptSource] to [replAfterFeed].
     */
    private var currentSource: PromptSource = promptSource

    /**
     * The portable per-turn core. Built once from this agent's fixed model
     * surface ([llmApi]) and generation knobs; [send] delegates wire
     * assembly, the LLM call and stage-signal parsing to it. Session
     * concerns (history, footer, timing, stage validation) stay here in the
     * loop, so the responder remains free of any session state.
     */
    private val responder = AgentResponder(
        AgentConfig(llmApi = llmApi, params = cliArgs.toGenerationParams()),
    )

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
        val modelId = cliArgs.modelProvider.modelId
        // Per-turn strategy side effect (rolling-summary compaction, facts
        // extraction, …) runs BEFORE the request is built and before this
        // turn's pair is appended: historyStore.messages is even-length here
        // (pairs are appended only on success), which the strategies'
        // role-alternation invariant relies on. No-op for FullHistory.
        historyStore?.let { strategy.onTurn(TurnContext(it, llmApi, prompt, modelId)) }
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
        val memoryLayer = memory?.memoryLayer() ?: emptyList()
        // Delegate the turn itself — wire assembly (memoryLayer + baseContext
        // + userTurn), the LLM call and stage-signal parsing — to the portable
        // responder. Timing wraps the whole call so the footer's duration is
        // unchanged.
        val (outcome, duration) = measureTimedValue {
            responder.respond(baseContext = baseContext, memoryLayer = memoryLayer, userTurn = userTurn)
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
     * Execute a REPL branch- or memory-management command. The DB work
     * (and disk work for memory) is suspend / blocking, so the
     * `PromptSource` stays pure and only classifies the line. Branch
     * commands need a persisted session; memory commands need a
     * configured [memory] provider — each prints an explanatory line
     * to stderr when its dependency is absent. Output otherwise mirrors
     * the existing `[branch] …` style.
     */
    private suspend fun handleBranchCommand(command: BranchCommand) {
        when (command) {
            BranchCommand.Checkpoint -> withHistoryStore { store ->
                System.err.println(
                    "[checkpoint] branch '${store.branchId}', ${store.messages.size} message(s) — " +
                        "use /branch <name> to fork a new branch from here"
                )
            }
            BranchCommand.ListBranches -> withHistoryStore { store ->
                val branches = store.branches()
                System.err.println(
                    "[branches] " + branches.joinToString(", ") { if (it == store.branchId) "* $it" else it }
                )
            }
            is BranchCommand.Branch -> withHistoryStore { store ->
                val name = command.name
                when {
                    !name.matches(BRANCH_NAME_REGEX) ->
                        System.err.println("[branch] invalid name '$name' (letters, digits, '_' or '-')")
                    name == store.branchId ->
                        System.err.println("[branch] already on '$name'")
                    name in store.branches() ->
                        System.err.println("[branch] '$name' already exists — use /switch $name")
                    else -> {
                        val copied = store.messages.size
                        store.fork(name)
                        System.err.println(
                            "[branch] forked '${store.branchId}' → '$name' ($copied message(s) copied); " +
                                "/switch $name to continue on it"
                        )
                    }
                }
            }
            is BranchCommand.Switch -> withHistoryStore { store ->
                val name = command.name
                when {
                    name == store.branchId -> System.err.println("[branch] already on '$name'")
                    name !in store.branches() ->
                        System.err.println("[branch] no such branch '$name' (use /branches to list)")
                    else -> {
                        store.switchTo(name)
                        strategy.rebind(store)
                        System.err.println("[branch] switched to '$name' (${store.messages.size / 2} prior turn(s))")
                    }
                }
            }
            BranchCommand.ShowMemory -> withMemory { mem ->
                System.err.println("[memory]\n${mem.describe()}")
            }
            is BranchCommand.SetProfile -> withMemory { mem ->
                if (command.text.isBlank()) {
                    System.err.println("[memory] /profile needs the new profile text")
                } else {
                    mem.store.saveProfile(command.text)
                    System.err.println("[memory] profile saved (${command.text.length} char(s))")
                }
            }
            is BranchCommand.AddProfileItem -> withMemory { mem ->
                if (command.text.isBlank()) {
                    System.err.println("[memory] /profile ${command.section.keyword} needs the new text")
                } else {
                    val updated = mem.store.addProfileItem(command.section, command.text)
                    val count = updated.items(command.section).size
                    System.err.println(
                        "[memory] profile.${command.section.keyword} += \"${command.text}\" ($count item(s) total)"
                    )
                }
            }
            is BranchCommand.ClearProfileSection -> withMemory { mem ->
                mem.store.clearProfileSection(command.section)
                System.err.println("[memory] profile.${command.section.keyword} cleared")
            }
            BranchCommand.ClearProfile -> withMemory { mem ->
                mem.store.clearProfile()
                System.err.println("[memory] profile cleared")
            }
            is BranchCommand.SwitchProfile -> withMemory { mem ->
                val name = command.name
                if (!isValidProfileName(name)) {
                    System.err.println("[memory] invalid profile name '$name' (alphanumeric / '_' / '-', up to 64 chars)")
                } else {
                    mem.setActiveProfile(name)
                    System.err.println("[memory] active profile → $name")
                }
            }
            BranchCommand.ListProfiles -> withMemory { mem ->
                val names = mem.store.listProfileNames()
                val active = mem.activeProfileName()
                if (names.isEmpty()) System.err.println("[memory] no named profiles")
                else {
                    System.err.println("[memory] profiles:")
                    names.forEach { name ->
                        val marker = if (name == active) "* " else "  "
                        System.err.println("  $marker$name")
                    }
                }
            }
            is BranchCommand.ShowProfile -> withMemory { mem ->
                val name = command.name
                val data = mem.store.loadNamedProfile(name)
                if (data == null) {
                    System.err.println("[memory] profile '$name' is empty or absent")
                } else {
                    System.err.println("[profile:$name]")
                    data.freeText?.takeIf { it.isNotBlank() }?.let { System.err.println(it.trim()) }
                    for (section in ProfileSection.entries) {
                        val items = data.items(section)
                        if (items.isEmpty()) continue
                        System.err.println("${section.keyword}: ${items.joinToString(", ")}")
                    }
                }
            }
            is BranchCommand.TouchProfile -> withMemory { mem ->
                val name = command.name
                if (!isValidProfileName(name)) {
                    System.err.println("[memory] invalid profile name '$name'")
                } else {
                    mem.store.touchNamedProfile(name)
                    System.err.println("[memory] profile '$name' ready (use /profile-use $name to activate)")
                }
            }
            is BranchCommand.AddNamedProfileItem -> withMemory { mem ->
                val name = command.name
                if (!isValidProfileName(name)) {
                    System.err.println("[memory] invalid profile name '$name'")
                } else if (command.text.isBlank()) {
                    System.err.println("[memory] /profile $name ${command.section.keyword} needs the new text")
                } else {
                    val updated = mem.store.addNamedProfileItem(name, command.section, command.text)
                    val count = updated.items(command.section).size
                    System.err.println(
                        "[memory] profile.$name.${command.section.keyword} += \"${command.text}\" ($count item(s) total)"
                    )
                }
            }
            is BranchCommand.ClearNamedProfileSection -> withMemory { mem ->
                mem.store.clearNamedProfileSection(command.name, command.section)
                System.err.println("[memory] profile.${command.name}.${command.section.keyword} cleared")
            }
            is BranchCommand.ClearNamedProfile -> withMemory { mem ->
                val removed = mem.store.clearNamedProfile(command.name)
                if (removed) System.err.println("[memory] profile '${command.name}' removed")
                else System.err.println("[memory] no profile named '${command.name}'")
            }
            is BranchCommand.AddRule -> withMemory { mem ->
                if (command.text.isBlank()) {
                    System.err.println("[memory] /rule needs the new rule text")
                } else {
                    val rule = mem.store.addRule(command.text)
                    System.err.println("[memory] rule ${rule.id} added")
                }
            }
            is BranchCommand.SetTask -> withMemory { mem ->
                val id = command.taskId
                if (id.isBlank()) {
                    System.err.println("[memory] /task needs a task id")
                } else {
                    mem.setTask(id)
                    // Touch-create so the file is visible on `/memory` and on
                    // disk even before the first note is appended. A new task
                    // starts at the initial FSM stage so it has formalized
                    // state from turn one.
                    val created = mem.store.loadTask(id) == null
                    if (created) mem.store.saveTask(TaskNotes(taskId = id, stage = TaskStage.INITIAL))
                    System.err.println(
                        "[memory] active task → $id" +
                            if (created) " (new, stage ${TaskStage.INITIAL.keyword})" else ""
                    )
                }
            }
            is BranchCommand.AppendTaskNote -> withMemory { mem ->
                val active = mem.activeTaskId()
                when {
                    active == null ->
                        System.err.println("[memory] /task-note needs an active task — set one with /task <id>")
                    command.note.isBlank() ->
                        System.err.println("[memory] /task-note needs the note text")
                    else -> {
                        mem.store.appendTaskNote(active, command.note)
                        System.err.println("[memory] note appended to task '$active'")
                    }
                }
            }
            BranchCommand.PauseTask -> withMemory { mem -> togglePause(mem, paused = true) }
            BranchCommand.ResumeTask -> withMemory { mem -> togglePause(mem, paused = false) }
            is BranchCommand.SetMemoryMode -> withMemory { mem ->
                mem.setMode(command.mode)
                System.err.println("[memory] mode → ${command.mode.name.lowercase()}")
            }
        }
    }

    private inline fun withHistoryStore(block: (HistoryStore) -> Unit) {
        val store = historyStore
        if (store == null) {
            System.err.println("[branch] branch commands need a persisted session")
        } else {
            block(store)
        }
    }

    private inline fun withMemory(block: (MemoryProvider) -> Unit) {
        val mem = memory
        if (mem == null) {
            System.err.println("[memory] memory commands need -memory-mode <preamble|system> at startup")
        } else {
            block(mem)
        }
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

    /**
     * Flip the active task's `paused` flag (`/task-pause` / `/task-resume`).
     * Paused tasks hold their stage — [maybeAdvanceTaskStage] skips them — so a
     * task can be parked at any stage and picked up later. No active task → an
     * explanatory line, no write.
     */
    private fun togglePause(mem: MemoryProvider, paused: Boolean) {
        val id = mem.activeTaskId()
        if (id == null) {
            System.err.println("[task] no active task — set one with /task <id>")
            return
        }
        val task = mem.store.loadTask(id) ?: TaskNotes(id, stage = TaskStage.INITIAL)
        mem.store.saveTask(task.copy(paused = paused))
        val word = if (paused) "paused" else "resumed"
        System.err.println("[task] $word — task '$id' at stage ${task.stage?.keyword ?: "(none)"}")
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
                    "session: turns=${s.turns} " + formatSessionTokens(s) +
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
                formatSessionTokens(stats) +
                "  cost=$costStr" +
                (if (pricing == null) "  (current model has no pricing entry)" else "")
        )
    }
}

/**
 * Threshold (% of context window) above which [Agent.printFooter] emits
 * a `[warning] context window …% full` line to stderr.
 */
private const val CONTEXT_WARN_PCT: Double = 90.0

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

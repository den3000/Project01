package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.shared.pricing.PricingRegistry

/**
 * Holds the conversation loop as MVI. A single `StateFlow<UiState>` is written
 * ONLY here (so the TUI's concurrent input collectors never race over a shared
 * var), one-shot effects go out on a channel, and [run] drives an
 * [IntentSource] from hydration through the final summary. Views observe
 * [state] and feed intents through the source — they aren't called back.
 *
 * This is the orchestration lifted out of `SessionLoop.run` / `drive`; the
 * per-turn engine is [TurnEngine] and command execution is [CommandRunner].
 * The view-model knows each turn's outcome from [TurnResult], so the old
 * `observeReply` / `notifyTurnFailed` source hooks are gone — `/reuse` reads
 * [lastReply], and feed continuation keys off the source's own `terminated`.
 */
internal class SessionViewModel(
    private val cliArgs: CliArgs.PromptCommand,
    private val engine: TurnEngine,
    private val commandRunner: CommandRunner,
    private val historyStore: HistoryStore?,
    private val memory: MemoryProvider?,
    private val strategy: ContextStrategy,
) {
    val state: StateFlow<UiState>
        field = MutableStateFlow(UiState())

    val effects: ReceiveChannel<UiEffect>
        field = Channel<UiEffect>(Channel.BUFFERED)

    /** Last successful model reply, cached for the stdin REPL's `/reuse`. */
    var lastReply: String? = null
        private set

    /**
     * Run the whole session: hydrate (resume banners), opening turn, drive
     * [primary] to a stop, optionally hand off to [followUp] (the feed→REPL
     * transition), then a final summary + Exit. OneShot stops right after the
     * opening turn — no loop, no summary (parity with the old `run`).
     */
    suspend fun run(primary: IntentSource, followUp: IntentSource? = null) {
        hydrate()
        if (!runTurn(cliArgs.prompt)) primary.onTurnFailed()
        if (cliArgs is CliArgs.OneShot) {
            effects.send(UiEffect.Exit)
            return
        }
        try {
            drive(primary)
            if (followUp != null) {
                val label =
                    if (primary.terminated) "[feed aborted — interim summary]" else "[feed done — interim summary]"
                emitSummary(label)
                appendNotice("[continuing in REPL — type /exit or /quit to leave]")
                drive(followUp)
            }
        } finally {
            emitSummary("[session-summary]")
            effects.send(UiEffect.Exit)
        }
    }

    private suspend fun drive(source: IntentSource) {
        while (true) {
            when (val intent = source.next()) {
                null, UiIntent.Exit -> return
                is UiIntent.Submit -> if (!runTurn(intent.text)) source.onTurnFailed()
                UiIntent.Reuse -> lastReply?.let { runTurn(it) }
                is UiIntent.SlashCommand -> runCommand(intent.command)
            }
        }
    }

    /** Run one turn; returns true on success, false on failure (lets a feed source abort). */
    private suspend fun runTurn(prompt: String): Boolean {
        state.update { it.copy(busy = true, lines = it.lines + UiLine.User(prompt)) }
        return when (val result = engine.turn(prompt)) {
            is TurnResult.Ok -> {
                lastReply = result.reply
                state.update {
                    it.copy(
                        busy = false,
                        lines = it.lines + UiLine.Turn(result),
                        stats = result.session ?: it.stats,
                    )
                }
                true
            }
            is TurnResult.Failed -> {
                state.update { it.copy(busy = false, lines = it.lines + UiLine.Error(result.reason)) }
                false
            }
        }
    }

    private suspend fun runCommand(command: BranchCommand) {
        commandRunner.run(command).forEach { appendNotice(it) }
    }

    private fun appendNotice(text: String) = state.update { it.copy(lines = it.lines + UiLine.Notice(text)) }

    /** Resume banners: prior-turn count + accumulated totals, and the active task's stage. */
    private suspend fun hydrate() {
        historyStore?.let { store ->
            store.load()
            if (store.messages.isNotEmpty()) {
                val s = store.stats
                appendNotice(
                    "[session] resumed: ${store.messages.size / 2} prior turn(s), " +
                        "tokens so far: total=${s.totalTokens}, cost=${formatCost(s.totalCostUsd, knownPricing = true)}"
                )
            }
            strategy.rebind(store)
        }
        memory?.activeTaskId()?.let { id ->
            memory.store.loadTask(id)?.let { task ->
                task.stage?.let { stage ->
                    appendNotice(
                        "[task] resuming '$id' — stage ${stage.keyword}" +
                            (if (task.paused) " (paused)" else "") +
                            (task.goal?.let { ", goal: $it" } ?: "")
                    )
                }
            }
        }
    }

    /** Append a session-summary line (final or feed-interim). No-op without history. */
    private fun emitSummary(label: String) {
        val stats = historyStore?.stats?.snapshot() ?: return
        appendNotice(formatSummary(stats, cliArgs.modelProvider.modelId, label))
    }
}

/**
 * Format a `[session-summary]` / interim line. We always print the numeric
 * cost (per-row cost is already baked into the totals), only noting when the
 * current model has no pricing entry.
 */
private fun formatSummary(s: SessionStatsSnapshot, modelId: String, label: String): String {
    val pricing = PricingRegistry.lookup(modelId)
    val costStr = if (s.turns == 0) "$0.00" else "%.5f USD".format(s.costUsd)
    return "$label turns=${s.turns}  " + formatSessionTokens(s) + "  cost=$costStr" +
        (if (pricing == null) "  (current model has no pricing entry)" else "")
}

package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.shared.memory.MemoryMode
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
    /** Mirrors `routedAgents.isNotEmpty()` — drives whether a reply carries an [AgentRef] tag. */
    private val multiAgent: Boolean,
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
                emitSummary(label, final = false)
                appendNotice("[continuing in REPL — type /exit or /quit to leave]")
                drive(followUp)
            }
        } finally {
            emitSummary("[session-summary]", final = true)
            effects.send(UiEffect.Exit)
        }
    }

    private suspend fun drive(source: IntentSource) {
        while (true) {
            when (val intent = source.next()) {
                null, UiIntent.Exit -> return
                is UiIntent.Submit -> when (val overlay = state.value.overlay) {
                    null -> if (!runTurn(intent.text)) source.onTurnFailed()
                    is Overlay.Picker -> selectPicker(overlay, intent.text)
                    is Overlay.Palette -> selectPalette(overlay, intent.text)
                }
                UiIntent.Reuse -> lastReply?.let { runTurn(it) }
                is UiIntent.SlashCommand -> runCommand(intent.command)
                is UiIntent.OpenPicker -> openPicker(intent.kind)
                UiIntent.OpenPalette -> openPalette()
                UiIntent.OverlayUp -> state.update { it.copy(overlay = it.overlay?.moved(-1)) }
                UiIntent.OverlayDown -> state.update { it.copy(overlay = it.overlay?.moved(+1)) }
                UiIntent.OverlayCancel -> state.update { it.copy(overlay = null) }
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
                        lines = it.lines + result.toLines(),
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

    /**
     * Decompose a finished turn into presentation lines (oldest first, so
     * PlainView's stdout/stderr ordering is preserved): the reply (+ agent
     * identity in multi-agent), the stats footer, then — only when they
     * happened — a judge breach and a task-stage move.
     */
    private fun TurnResult.Ok.toLines(): List<UiLine> = buildList {
        add(UiLine.Assistant(reply, if (multiAgent) AgentRef(profileName, modelId) else null))
        add(UiLine.Turn(usage, modelId, durationMs, session))
        if (!verdict.passed) add(UiLine.Judge(judgeModelId, verdict.violations))
        if (stageAdvance != StageAdvance.None) add(UiLine.Stage(stageAdvance))
    }

    /**
     * Run a `/`-command and lay its status line(s) into the `state` lane — the
     * TUI shows them as a `state │ …` column, consistent with the resume banner
     * (a command result is a session-state report). PlainView is unaffected:
     * State and Notice both go to stderr verbatim.
     */
    private suspend fun runCommand(command: BranchCommand) {
        commandRunner.run(command).forEach { appendState(it) }
    }

    /** A transient notice (feed→REPL transition, interim feed summary) — a plain line, no `state │` column. */
    private fun appendNotice(text: String) = state.update { it.copy(lines = it.lines + UiLine.Notice(text)) }

    /** A session-state line (resume banner, `/`-command results, picker status) — its own `state │` lane. */
    private fun appendState(text: String) = state.update { it.copy(lines = it.lines + UiLine.State(text)) }

    /**
     * Open a modal picker, populating its options from the live session: named
     * profiles / task ids / branches / memory modes. A missing dependency (no
     * memory provider, no persisted session) or an empty list yields an
     * explanatory notice instead — wording mirrors [CommandRunner] so the TUI
     * picker and the REPL command agree.
     */
    private suspend fun openPicker(kind: PickerKind) {
        val options: List<String>? = when (kind) {
            PickerKind.Profile -> memory?.store?.listProfileNames()
            PickerKind.Task -> memory?.store?.listTaskIds()
            PickerKind.MemoryMode -> memory?.let { MemoryMode.entries.map { m -> m.name.lowercase() } }
            PickerKind.Branch -> historyStore?.branches()
        }
        when {
            options == null -> appendState(
                when (kind) {
                    PickerKind.Branch -> "[branch] branch commands need a persisted session"
                    else -> "[memory] memory commands need -memory-mode <preamble|system> at startup"
                }
            )
            options.isEmpty() -> appendState(
                when (kind) {
                    PickerKind.Profile -> "[memory] no named profiles — create one with /profile <name> <section> <text>"
                    PickerKind.Task -> "[memory] no tasks yet — create one with /task <id>"
                    else -> "[memory] nothing to pick"
                }
            )
            else -> state.update { it.copy(overlay = Overlay.Picker(kind, options)) }
        }
    }

    /**
     * Resolve the open [picker] against the typed [text] (empty → cursor row, a
     * 1-based number → that row, anything else → cancel). A valid choice closes
     * the picker and runs the mapped [BranchCommand] through the same
     * [CommandRunner] the REPL uses — the picker adds no new domain logic.
     */
    private suspend fun selectPicker(picker: Overlay.Picker, text: String) {
        val idx = picker.selectionIndex(text)
        state.update { it.copy(overlay = null) }
        if (idx != null) runCommand(pickerCommand(picker.kind, picker.options[idx]))
    }

    /** Map a picked option to the existing command that applies it. */
    private fun pickerCommand(kind: PickerKind, option: String): BranchCommand = when (kind) {
        PickerKind.Profile -> BranchCommand.SwitchProfile(option)
        PickerKind.Task -> BranchCommand.SetTask(option)
        PickerKind.Branch -> BranchCommand.Switch(option)
        PickerKind.MemoryMode -> BranchCommand.SetMemoryMode(MemoryMode.valueOf(option.uppercase()))
    }

    /** Open the command palette over the full catalog (static — no dependency to gate on). */
    private fun openPalette() = state.update { it.copy(overlay = Overlay.Palette(commandCatalog())) }

    /**
     * Resolve the open command [palette] against [text] (same row-selection rule
     * as a picker). A chosen entry runs its command, opens a picker, pre-fills
     * the input (a [UiEffect.Prefill] the TUI consumes), or resends the last
     * reply — every action routes through machinery that already exists.
     */
    private suspend fun selectPalette(palette: Overlay.Palette, text: String) {
        val entry = palette.selectionIndex(text)?.let { palette.entries.getOrNull(it) }
        state.update { it.copy(overlay = null) }
        when (val action = entry?.action) {
            null -> Unit
            is PaletteAction.Run -> runCommand(action.command)
            is PaletteAction.Pick -> openPicker(action.kind)
            is PaletteAction.Prefill -> effects.send(UiEffect.Prefill(action.stub))
            PaletteAction.Reuse -> lastReply?.let { runTurn(it) }
        }
    }

    /** Resume banners: prior-turn count + accumulated totals, and the active task's stage. */
    private suspend fun hydrate() {
        historyStore?.let { store ->
            store.load()
            if (store.messages.isNotEmpty()) {
                val s = store.stats
                appendState(
                    "[session] resumed: ${store.messages.size / 2} prior turn(s), " +
                        "tokens so far: total=${s.totalTokens}, cost=${"$%.5f".format(s.totalCostUsd)}"
                )
            }
            strategy.rebind(store)
        }
        memory?.activeTaskId()?.let { id ->
            memory.store.loadTask(id)?.let { task ->
                task.stage?.let { stage ->
                    appendState(
                        "[task] resuming '$id' — stage ${stage.keyword}" +
                            (if (task.paused) " (paused)" else "") +
                            (task.goal?.let { ", goal: $it" } ?: "")
                    )
                }
            }
        }
    }

    /**
     * Append a summary line. The [final] summary becomes a [UiLine.Summary] (the
     * TUI drops it — its stats panel already shows the totals); a feed-interim
     * summary stays a [UiLine.Notice]. No-op without history.
     */
    private fun emitSummary(label: String, final: Boolean) {
        val stats = historyStore?.stats?.snapshot() ?: return
        val text = formatSummary(stats, cliArgs.modelProvider.modelId, label)
        val line = if (final) UiLine.Summary(text) else UiLine.Notice(text)
        state.update { it.copy(lines = it.lines + line) }
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
    val tokens = buildString {
        append("prompt=${s.promptTokens}  output=${s.outputTokens}")
        if (s.thoughtsTokens > 0) append("  thoughts=${s.thoughtsTokens}")
        append("  total=${s.totalTokens}")
    }
    return "$label turns=${s.turns}  $tokens  cost=$costStr" +
        (if (pricing == null) "  (current model has no pricing entry)" else "")
}

package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.shared.invariant.InvariantViolation
import ru.den.writes.code.project01.shared.llm.Usage

/**
 * Immutable UI state for one running session. Observed by a renderer (PlainView /
 * TuiView family) and written ONLY by `SessionViewModel` — a single writer means
 * the TUI's concurrent input collectors never race over a shared `var`. Snapshots
 * all the way down (see [SessionStatsSnapshot]).
 */
internal data class UiState(
    /** The transcript so far, oldest first. */
    val lines: List<UiLine> = emptyList(),
    /** A turn is in flight (the model is being called). Drives the TUI spinner. */
    val busy: Boolean = false,
    /** Latest running totals, for the TUI stats panel. Null before the first turn. */
    val stats: SessionStatsSnapshot? = null,
    /**
     * An open modal overlay, or null. TUI-only: while non-null the live section
     * shows it instead of the stats panel, and typed input drives it rather than
     * submitting a turn. PlainView ignores it (it reads only [lines]).
     */
    val overlay: Overlay? = null,
)

/**
 * What a modal picker selects. Each kind carries its panel title and tells the
 * view-model which existing [BranchCommand] a chosen option maps to — the picker
 * is a TUI-only input modality over commands that already exist, not new logic.
 */
internal enum class PickerKind(val title: String) {
    Profile("profile  ↑↓ · Enter · Esc"),
    Task("task  ↑↓ · Enter · Esc"),
    Branch("branch  ↑↓ · Enter · Esc"),
    MemoryMode("memory-mode  ↑↓ · Enter · Esc"),
}

/**
 * An open modal overlay pinned in the TUI's bottom block. Variants share a
 * [cursor] and [moved] navigation (the arrow keys) so the renderer and the
 * view-model treat them uniformly; each variant decides what a selection does.
 * Pure (no terminal), so the view-model — the single writer — drives and tests
 * it offline. Ported from the cliTui spike, generalized for more than one kind.
 */
internal sealed interface Overlay {
    val cursor: Int

    /** Number of selectable rows — the bound for [selectionIndex]. */
    val size: Int

    /** Move the cursor by [delta], wrapping at both ends. */
    fun moved(delta: Int): Overlay

    /**
     * Map the input box to a row: empty → the cursor, a 1-based number → that
     * row, anything else → null. Shared by every overlay — selection works the
     * same regardless of what the chosen row then does.
     */
    fun selectionIndex(text: String): Int? = when {
        text.isEmpty() -> cursor
        (text.toIntOrNull() ?: 0) in 1..size -> text.toInt() - 1
        else -> null
    }

    /**
     * Pick one option from a list, applying an existing command: the view-model
     * maps the chosen [options] entry to the [BranchCommand] for its [kind].
     */
    data class Picker(
        val kind: PickerKind,
        val options: List<String>,
        override val cursor: Int = 0,
    ) : Overlay {
        override val size: Int get() = options.size
        override fun moved(delta: Int): Picker =
            if (options.isEmpty()) this else copy(cursor = (cursor + delta).mod(options.size))
    }

    /**
     * The command palette: every `/`-command as a [CommandEntry] so the many
     * commands are discoverable. A selected entry runs its command, opens a
     * picker, or pre-fills the input — see [PaletteAction].
     */
    data class Palette(
        val entries: List<CommandEntry>,
        override val cursor: Int = 0,
    ) : Overlay {
        override val size: Int get() = entries.size
        override fun moved(delta: Int): Palette =
            if (entries.isEmpty()) this else copy(cursor = (cursor + delta).mod(entries.size))
    }
}

/** One row in the [Overlay.Palette]: the command [name], a one-line [help], and what selecting it does. */
internal data class CommandEntry(val name: String, val help: String, val action: PaletteAction)

/** What selecting a [CommandEntry] does — all over commands / intents that already exist. */
internal sealed interface PaletteAction {
    /** Run a no-argument command (e.g. `/checkpoint`, `/branches`). */
    data class Run(val command: BranchCommand) : PaletteAction

    /** Open a picker for a command that chooses from a set (e.g. `/profile-use`). */
    data class Pick(val kind: PickerKind) : PaletteAction

    /** Pre-fill the input with [stub] so the user types the argument (e.g. `/rule `). */
    data class Prefill(val stub: String) : PaletteAction

    /** Resend the last model reply (`/reuse`). */
    data object Reuse : PaletteAction
}

/**
 * The answering agent's identity, attached to an [UiLine.Assistant] only when a
 * tag should show (multi-agent sessions). Its presence — not a driver flag — is
 * what tells a view to render the `[[AGENT:]]` tag.
 */
internal data class AgentRef(val profileName: String?, val modelId: String)

/**
 * One rendered unit in the transcript. Each variant carries exactly the data its
 * view needs to draw it — no reference to the domain [TurnResult]. The
 * view-model decomposes a finished turn into [Assistant] + [Turn] (+ [Judge] /
 * [Stage]); a view maps each variant to its renderer.
 */
internal sealed interface UiLine {
    /**
     * The user's submitted prompt, echoed into the transcript. The TUI renders
     * it (raw mode suppresses the terminal echo); PlainView skips it (the
     * terminal already shows the typed line, so echoing would double it).
     */
    data class User(val text: String) : UiLine

    /**
     * The model's reply plus the answering agent's identity. [agent] is non-null
     * only in a multi-agent session, in which case the view shows the
     * `[[AGENT:]]` tag; null prints just the reply.
     */
    data class Assistant(val reply: String, val agent: AgentRef?) : UiLine

    /**
     * The per-turn stats footer: token [usage] (null when the provider returned
     * text without counts), [modelId] (for the pricing lookup), call
     * [durationMs], and the running [session] snapshot (null in OneShot).
     */
    data class Turn(
        val usage: Usage?,
        val modelId: String,
        val durationMs: Long,
        val session: SessionStatsSnapshot?,
    ) : UiLine

    /**
     * An invariant-judge breach: [judgeModelId] is the model that flagged it (the
     * TUI tags the block with it; PlainView ignores it) and [violations] are the
     * breaches. Emitted only on a breach (so [violations] is never empty).
     */
    data class Judge(val judgeModelId: String?, val violations: List<InvariantViolation>) : UiLine

    /** A task-stage FSM move. Emitted only when the stage changed or a move was rejected. */
    data class Stage(val advance: StageAdvance) : UiLine

    /** A failed turn: the provider error. */
    data class Error(val reason: String) : UiLine

    /**
     * A session-state line: the resume banner, a `/`-command result, or a
     * picker's status (e.g. why it couldn't open). Pre-formatted with its own
     * `[tag]`; PlainView → stderr, the TUI shows it in a `state │ …` column.
     */
    data class State(val text: String) : UiLine

    /**
     * The final session summary. PlainView → stderr; the TUI drops it — its live
     * stats panel already shows the totals.
     */
    data class Summary(val text: String) : UiLine

    /**
     * A transient notice that's neither session state nor summary: the feed→REPL
     * transition or an interim feed summary. Carries its own `[tag]`; PlainView →
     * stderr, the TUI shows a plain line (no `state │` column).
     */
    data class Notice(val text: String) : UiLine
}

/** What a view sends to the view-model. Semantics, not raw keys. */
internal sealed interface UiIntent {
    /**
     * Send [text] as the next user turn — or, when an overlay is open, select
     * from it (empty → the cursor row, a number → that row). The view-model
     * branches on [UiState.overlay], so a view needn't know which mode it's in.
     */
    data class Submit(val text: String) : UiIntent

    /** Run a classified `/`-command (see [parseSlashCommand]). */
    data class SlashCommand(val command: BranchCommand) : UiIntent

    /** Resend the last model reply (REPL `/reuse`); a no-op if there's none yet. */
    data object Reuse : UiIntent

    /** Leave the session (REPL `/exit` / EOF). */
    data object Exit : UiIntent

    /** Open a modal picker of the given [kind] (TUI only). */
    data class OpenPicker(val kind: PickerKind) : UiIntent

    /** Open the command palette (TUI only). */
    data object OpenPalette : UiIntent

    /** Move the open overlay's cursor up / down (wraps). No-op if none is open. */
    data object OverlayUp : UiIntent
    data object OverlayDown : UiIntent

    /** Close the open overlay without selecting. No-op if none is open. */
    data object OverlayCancel : UiIntent
}

/**
 * One-shot side effects — not state, so they don't replay on re-render.
 * The session summary is NOT an effect: it's a [UiLine.Notice] so its
 * stderr ordering against the surrounding `[continuing in REPL …]` line is
 * preserved through the single transcript.
 */
internal sealed interface UiEffect {
    /** The session is over; the view should stop. */
    data object Exit : UiEffect

    /**
     * Pre-fill the TUI input line with [text] — a command stub (e.g. `/rule `)
     * the user completes with an argument. Emitted when a free-text command is
     * chosen from the palette; the TUI consumes it, other views ignore it.
     */
    data class Prefill(val text: String) : UiEffect
}

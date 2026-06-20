package ru.den.writes.code.project01.cliJvm

/**
 * Immutable UI state for one running session. Observed by a view (PlainView /
 * TuiView) and written ONLY by `SessionViewModel` — a single writer means the
 * TUI's concurrent input collectors never race over a shared `var`. Snapshots
 * all the way down (see [TurnResult] / [SessionStatsSnapshot]).
 */
internal data class UiState(
    /** The transcript so far, oldest first. */
    val lines: List<UiLine> = emptyList(),
    /** A turn is in flight (the model is being called). Drives the TUI spinner. */
    val busy: Boolean = false,
    /** Latest running totals, for the TUI stats panel. Null before the first turn. */
    val stats: SessionStatsSnapshot? = null,
)

/** One rendered unit in the transcript. */
internal sealed interface UiLine {
    /**
     * The user's submitted prompt, echoed into the transcript. The TUI renders
     * it (raw mode suppresses the terminal echo); PlainView skips it (the
     * terminal already shows the typed line, so echoing would double it).
     */
    data class User(val text: String) : UiLine

    /** A completed turn: reply + footer (+ any task-stage move). */
    data class Turn(val outcome: TurnResult.Ok) : UiLine

    /** A failed turn: the provider error. */
    data class Error(val reason: String) : UiLine

    /**
     * A pre-formatted status line (resume banner, branch / memory command
     * result, …). Carries its own `[tag]` prefix; PlainView sends it to
     * stderr, TuiView drops it into the transcript.
     */
    data class Notice(val text: String) : UiLine
}

/** What a view sends to the view-model. Semantics, not raw keys. */
internal sealed interface UiIntent {
    /** Send [text] as the next user turn. */
    data class Submit(val text: String) : UiIntent

    /** Run a classified `/`-command (see [parseSlashCommand]). */
    data class SlashCommand(val command: BranchCommand) : UiIntent

    /** Resend the last model reply (REPL `/reuse`); a no-op if there's none yet. */
    data object Reuse : UiIntent

    /** Leave the session (REPL `/exit` / EOF). */
    data object Exit : UiIntent
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
}

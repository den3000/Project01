package ru.den.writes.code.project01.cliJvm

/**
 * The outcome of [PromptSource.nextPrompt]: a user prompt to send, a REPL
 * command for [CommandRunner] to execute, or a signal to stop the loop
 * (REPL `/quit` / `/exit`, file exhausted, or an aborted feed).
 */
public sealed interface PromptResult {
    public data class Prompt(val text: String) : PromptResult
    public data class Command(val command: SessionCommand) : PromptResult

    /** REPL `/reuse`: resend the last model reply (the view-model holds it). */
    public data object Reuse : PromptResult
    public data object Stop : PromptResult
}

/**
 * What drives the next user turn at each loop iteration of [SessionViewModel].
 *
 * Production implementations live in the CLI app (stdin REPL + file-feed
 * sources); tests pass their own one-shot stubs.
 */
public interface PromptSource {
    /**
     * The next [PromptResult]: a [PromptResult.Prompt] to send, a
     * [PromptResult.Command] for the agent to run, or [PromptResult.Stop]
     * when the source is exhausted (REPL EOF/quit, file consumed, abort).
     */
    public fun nextPrompt(): PromptResult

    /**
     * Hook the agent calls when a turn failed (provider returned an
     * error). Default is a no-op — REPL sources just let the user try
     * again. Feed sources use it to flip an abort flag so the next
     * [nextPrompt] returns [PromptResult.Stop] and the feed loop stops
     * gracefully instead of feeding more context into an already-broken
     * conversation.
     */
    public fun notifyTurnFailed() {}

    /**
     * `true` if this source signalled [PromptResult.Stop] because it was
     * aborted (e.g. via [notifyTurnFailed]) rather than naturally exhausted
     * (file done, REPL `/exit`). Lets the agent distinguish "loop ended
     * because the data ran out, switch to next phase" from "loop ended
     * because something broke, stop here".
     *
     * Default `false`: most sources don't have an abort concept and
     * just naturally run out.
     */
    public val terminated: Boolean get() = false
}

/**
 * Every `/`-command as a palette row — the single source the TUI command palette
 * lists, in the catalog grammar. Ordered pickers → no-arg → free-text (prefill).
 * `/exit` / `/quit` are omitted (one keystroke away, handled before this), as is
 * the bare prompt.
 */
public fun commandCatalog(): List<CommandEntry> = listOf(
    CommandEntry("/profile", "switch the active named profile", PaletteAction.Pick(PickerKind.Profile)),
    CommandEntry("/task", "set or switch the active task", PaletteAction.Pick(PickerKind.Task)),
    CommandEntry("/branch", "switch the session branch", PaletteAction.Pick(PickerKind.Branch)),
    CommandEntry("/agent mode", "switch the memory injection mode", PaletteAction.Pick(PickerKind.MemoryMode)),
    CommandEntry("/branch show", "show the current branch and message count", PaletteAction.Run(SessionCommand.Checkpoint)),
    CommandEntry("/memory", "show the active memory layer", PaletteAction.Run(SessionCommand.ShowMemory)),
    CommandEntry("/task pause", "pause the active task (hold its stage)", PaletteAction.Run(SessionCommand.PauseTask)),
    CommandEntry("/task resume", "resume the active task", PaletteAction.Run(SessionCommand.ResumeTask)),
    CommandEntry("/reuse", "resend the last model reply", PaletteAction.Reuse),
    CommandEntry("/rule", "add a memory rule", PaletteAction.Prefill("/rule ")),
    CommandEntry("/task note", "append a note to the active task", PaletteAction.Prefill("/task note ")),
    CommandEntry("/branch <name>", "fork a new branch from here", PaletteAction.Prefill("/branch ")),
    CommandEntry("/profile <section>", "edit a profile section", PaletteAction.Prefill("/profile ")),
    CommandEntry("/profile show", "show a named profile", PaletteAction.Prefill("/profile show ")),
)

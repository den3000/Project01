package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.cliJvm.command.ControlsToBranchCommand
import ru.den.writes.code.project01.cliJvm.command.ScheduleSpec
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.ProfileSection
import java.io.BufferedReader
import java.io.Reader

/**
 * The outcome of [PromptSource.nextPrompt]: a user prompt to send, a REPL
 * branch-management command for [CommandRunner] to execute, or a signal to stop the
 * loop (REPL `/quit` / `/exit`, file exhausted, or an aborted feed).
 */
internal sealed interface PromptResult {
    data class Prompt(val text: String) : PromptResult
    data class Command(val command: BranchCommand) : PromptResult

    /** REPL `/reuse`: resend the last model reply (the view-model holds it). */
    data object Reuse : PromptResult
    data object Stop : PromptResult
}

/**
 * A branch-management or memory-management command typed at the REPL.
 * [StdinPromptSource] only classifies the line into one of these;
 * [CommandRunner] executes the (suspend) DB/disk work, so the source stays pure
 * and synchronous.
 */
internal sealed interface BranchCommand {
    data object Checkpoint : BranchCommand
    data object ListBranches : BranchCommand
    data class Branch(val name: String) : BranchCommand
    data class Switch(val name: String) : BranchCommand

    /** Delete one branch by name — the per-branch twin of `session clear`; never the active one. */
    data class DeleteBranch(val name: String) : BranchCommand
    /** Delete every branch except the current one. */
    data object ClearBranches : BranchCommand

    /** Print the active mode + the saved profile/rules/task. */
    data object ShowMemory : BranchCommand
    /** Append [text] to a [section] of the unnamed profile. */
    data class AddProfileItem(val section: ProfileSection, val text: String) : BranchCommand
    /** Empty one section of the unnamed profile; the rest survive. */
    data class ClearProfileSection(val section: ProfileSection) : BranchCommand
    /** Drop the unnamed profile entirely. */
    data object ClearProfile : BranchCommand

    // --- Named profiles --------------------------------------------
    /** Switch the active named profile (touch-creates if missing). */
    data class SwitchProfile(val name: String) : BranchCommand
    /** List every named profile under `profiles/`. */
    data object ListProfiles : BranchCommand
    /** Print one named profile's structure. */
    data class ShowProfile(val name: String) : BranchCommand
    /** Append a bullet to [section] of a named profile. */
    data class AddNamedProfileItem(val name: String, val section: ProfileSection, val text: String) : BranchCommand
    /** Empty a [section] of a named profile. */
    data class ClearNamedProfileSection(val name: String, val section: ProfileSection) : BranchCommand
    /** Delete the named profile file. */
    data class ClearNamedProfile(val name: String) : BranchCommand
    /** Delete every profile — all named ones and the unnamed default. */
    data object ClearAllProfiles : BranchCommand

    /** Append a new rule under `rules/`. */
    data class AddRule(val text: String) : BranchCommand
    /** Delete the rule with this id (three-digit prefix). */
    data class RemoveRule(val id: String) : BranchCommand
    /** Delete every rule. */
    data object ClearRules : BranchCommand
    /** Switch the active task id (creates an empty task file if absent). */
    data class SetTask(val taskId: String) : BranchCommand
    /** Append a note to the currently-active task. */
    data class AppendTaskNote(val note: String) : BranchCommand
    /** Pause the active task — hold its stage; auto-advance is suppressed. */
    data object PauseTask : BranchCommand
    /** Resume the active task — clear the pause flag; auto-advance resumes. */
    data object ResumeTask : BranchCommand
    /** Delete one task by id. */
    data class DeleteTask(val taskId: String) : BranchCommand
    /** Delete every task. */
    data object ClearTasks : BranchCommand
    /** Flip the memory-injection mode (PREAMBLE ↔ SYSTEM). */
    data class SetMemoryMode(val mode: MemoryMode) : BranchCommand

    /** Add a scheduled task in-session (`/schedule collect … | agent …`). */
    data class Schedule(val spec: ScheduleSpec) : BranchCommand
}

/**
 * What drives the next user turn at each loop iteration of [SessionViewModel].
 *
 * Production implementations:
 * - [StdinPromptSource] — interactive REPL, reads from stdin, handles
 *   `/quit`, `/exit`, `/reuse`.
 * - [ChunkedFilePromptSource] — feed mode, reads next N characters
 *   from a file and returns them as the next user prompt.
 * - [LineFilePromptSource] — feed mode, one line per turn.
 *
 * Tests pass their own one-shot stubs.
 */
internal interface PromptSource {
    /**
     * The next [PromptResult]: a [PromptResult.Prompt] to send, a
     * [PromptResult.Command] for the agent to run, or [PromptResult.Stop]
     * when the source is exhausted (REPL EOF/quit, file consumed, abort).
     */
    fun nextPrompt(): PromptResult

    /**
     * Hook the agent calls when a turn failed (provider returned an
     * error). Default is a no-op — REPL sources just let the user try
     * again. [ChunkedFilePromptSource] uses it to flip an abort flag so
     * the next [nextPrompt] returns [PromptResult.Stop] and the feed loop
     * stops gracefully instead of feeding more context into an
     * already-broken conversation.
     */
    fun notifyTurnFailed() {}

    /**
     * `true` if this source signalled [PromptResult.Stop] because it was aborted
     * (e.g. via [notifyTurnFailed]) rather than naturally exhausted
     * (file done, REPL `/exit`). Lets the agent distinguish "loop ended
     * because the data ran out, switch to next phase" from "loop ended
     * because something broke, stop here".
     *
     * Default `false`: most sources don't have an abort concept and
     * just naturally run out.
     */
    val terminated: Boolean get() = false
}

// ---- Commands handled by [StdinPromptSource] -----------------------

private const val QUIT_COMMAND = "/quit"
private const val EXIT_COMMAND = "/exit"
private const val REUSE_COMMAND = "/reuse"
private const val PROMPT_INDICATOR = "> "

/**
 * Reads prompts from an interactive terminal-like reader. Handles the
 * REPL niceties:
 *
 * - Prints a help banner + the `> ` indicator before each read.
 * - `/quit`, `/exit` or EOF → returns `null` (loop stops).
 * - `/reuse` → returns the cached model reply from the previous turn,
 *   or skips to reading the next line if no reply has happened yet.
 *
 * Owns no IO lifecycle — the [reader] is the caller's to close. In the
 * production wiring it's `System.in`, which stays open process-wide.
 */
internal class StdinPromptSource(private val reader: BufferedReader) : PromptSource {

    override fun nextPrompt(): PromptResult {
        while (true) {
            println(
                "Type a new prompt and press Enter.\n"
                    + "Type $QUIT_COMMAND or $EXIT_COMMAND to leave, $REUSE_COMMAND to resend the last reply.\n"
                    + "Branches: /branch, /branch <name>, /branch switch <name>, /branch show.\n"
                    + "Memory: /memory, /profile, /profile <name>, /profile show <name>,\n"
                    + "        /profile [<name>] <section> [\"<text>\"] (omit text to clear; /profile [<name>] clean),\n"
                    + "        /rule \"<text>\", /rule rm <id>, /task <id>, /task note \"<text>\",\n"
                    + "        /task pause, /task resume, /agent mode <preamble|system>."
            )
            print(PROMPT_INDICATOR)
            System.out.flush()
            val line = reader.readLine()?.trim() ?: return PromptResult.Stop  // EOF / Ctrl-D
            if (line.isEmpty()) continue
            if (
                line.equals(QUIT_COMMAND, ignoreCase = true)
                || line.equals(EXIT_COMMAND, ignoreCase = true)
            ) return PromptResult.Stop
            if (line.equals(REUSE_COMMAND, ignoreCase = true)) return PromptResult.Reuse
            parseSlashCommand(line)?.let { return PromptResult.Command(it) }
            return PromptResult.Prompt(line)
        }
    }
}

/** Shared catalog-backed classifier for the `/`-command (CMD) front. */
private val controlsToBranch = ControlsToBranchCommand()

/**
 * Classify a `/`-command typed at the REPL into a [BranchCommand], or null if
 * [line] isn't one (it falls through as a normal prompt). Top-level so both
 * [StdinPromptSource] and the TUI intent source share one classifier. Delegates
 * to the shared clicontrols catalog ([ControlsToBranchCommand]) on the command
 * front, so the `/`-grammar and the startup `-`-grammar stay one declarative
 * source. Multi-word values must be quoted (`/rule "no emojis"`), matching the
 * catalog tokenizer.
 */
internal fun parseSlashCommand(line: String): BranchCommand? = controlsToBranch.parse(line)

/**
 * Every `/`-command as a palette row — the single source the TUI command palette
 * lists, in the catalog grammar. Ordered pickers → no-arg → free-text (prefill).
 * `/exit` / `/quit` are omitted (one keystroke away, handled before this), as is
 * the bare prompt.
 */
internal fun commandCatalog(): List<CommandEntry> = listOf(
    CommandEntry("/profile", "switch the active named profile", PaletteAction.Pick(PickerKind.Profile)),
    CommandEntry("/task", "set or switch the active task", PaletteAction.Pick(PickerKind.Task)),
    CommandEntry("/branch", "switch the session branch", PaletteAction.Pick(PickerKind.Branch)),
    CommandEntry("/agent mode", "switch the memory injection mode", PaletteAction.Pick(PickerKind.MemoryMode)),
    CommandEntry("/branch show", "show the current branch and message count", PaletteAction.Run(BranchCommand.Checkpoint)),
    CommandEntry("/memory", "show the active memory layer", PaletteAction.Run(BranchCommand.ShowMemory)),
    CommandEntry("/task pause", "pause the active task (hold its stage)", PaletteAction.Run(BranchCommand.PauseTask)),
    CommandEntry("/task resume", "resume the active task", PaletteAction.Run(BranchCommand.ResumeTask)),
    CommandEntry("/reuse", "resend the last model reply", PaletteAction.Reuse),
    CommandEntry("/rule", "add a memory rule", PaletteAction.Prefill("/rule ")),
    CommandEntry("/task note", "append a note to the active task", PaletteAction.Prefill("/task note ")),
    CommandEntry("/branch <name>", "fork a new branch from here", PaletteAction.Prefill("/branch ")),
    CommandEntry("/profile <section>", "edit a profile section", PaletteAction.Prefill("/profile ")),
    CommandEntry("/profile show", "show a named profile", PaletteAction.Prefill("/profile show ")),
)

/**
 * Reads the next [chunkChars] **characters** (not bytes — Reader-level,
 * so UTF-8 multi-byte sequences stay intact) from [reader] and returns
 * them as the next user prompt, optionally wrapped in a fixed
 * [instruction] prefix.
 *
 * When the underlying stream is exhausted, [nextPrompt] returns `null`
 * and the agent loop stops naturally.
 *
 * Used for the overflow demo: point this at a large file with a
 * generous chunk size and the conversation will accumulate context
 * until the model's window can't take any more — at which point the
 * provider returns an error, Agent prints `[error]` and the loop
 * stops on the next `null` from this source (because a failed turn aborts
 * the feed source).
 *
 * The caller owns the [reader] lifecycle — wrap construction in a
 * `reader.use { ... }` block at the call site.
 */
internal class ChunkedFilePromptSource(
    private val reader: Reader,
    private val chunkChars: Int,
    private val instruction: String = "",
) : PromptSource {
    init {
        require(chunkChars > 0) { "chunkChars must be > 0, got $chunkChars" }
    }

    private val buffer = CharArray(chunkChars)
    private var aborted = false

    override val terminated: Boolean get() = aborted

    override fun notifyTurnFailed() {
        aborted = true
    }

    override fun nextPrompt(): PromptResult {
        if (aborted) return PromptResult.Stop
        // Loop until the reader fills at least one character or hits
        // EOF — read() can return 0 if the buffer has length 0 (we
        // guard against that above) or 0 chars are available right now
        // but the stream isn't done. For local files that doesn't
        // happen, but the JVM contract permits it.
        var read = 0
        while (read < buffer.size) {
            val n = reader.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
            if (read >= buffer.size) break
        }
        if (read == 0) return PromptResult.Stop
        val chunk = String(buffer, 0, read)
        return PromptResult.Prompt(if (instruction.isEmpty()) chunk else "$instruction\n\n$chunk")
    }
}

/**
 * Reads the next non-blank **line** from [reader] and returns it as the
 * next user prompt, optionally wrapped in a fixed [instruction] prefix.
 *
 * One line = one turn — the easy companion to [ChunkedFilePromptSource]'s
 * character chunks. Handy for scripting reproducible runs: write the
 * conversation one turn per line and feed it in. Blank / whitespace-only
 * lines are skipped (so you can space the script out for readability) and
 * each line is trimmed. When the stream is exhausted [nextPrompt] returns
 * `null` and the agent loop stops naturally.
 *
 * Same abort semantics as [ChunkedFilePromptSource]: a failed turn flips
 * [terminated] so the feed stops instead of pushing more lines into an
 * already-broken conversation. The caller owns the [reader] lifecycle —
 * wrap construction in a `reader.use { ... }` block at the call site.
 */
internal class LineFilePromptSource(
    private val reader: BufferedReader,
    private val instruction: String = "",
) : PromptSource {
    private var aborted = false

    override val terminated: Boolean get() = aborted

    override fun notifyTurnFailed() {
        aborted = true
    }

    override fun nextPrompt(): PromptResult {
        if (aborted) return PromptResult.Stop
        while (true) {
            val line = reader.readLine() ?: return PromptResult.Stop  // EOF
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue  // skip blank separator lines
            return PromptResult.Prompt(if (instruction.isEmpty()) trimmed else "$instruction\n\n$trimmed")
        }
    }
}

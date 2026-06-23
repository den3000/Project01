package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.ProfileCommand
import ru.den.writes.code.project01.shared.memory.ProfileSection
import ru.den.writes.code.project01.shared.memory.isValidProfileName
import ru.den.writes.code.project01.shared.memory.parseProfileCommand
import java.io.BufferedReader
import java.io.Reader

/**
 * The outcome of [PromptSource.nextPrompt]: a user prompt to send, a REPL
 * branch-management command for [SessionLoop] to execute, or a signal to stop the
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
 * [SessionLoop] executes the (suspend) DB/disk work, so the source stays pure
 * and synchronous.
 */
internal sealed interface BranchCommand {
    data object Checkpoint : BranchCommand
    data object ListBranches : BranchCommand
    data class Branch(val name: String) : BranchCommand
    data class Switch(val name: String) : BranchCommand

    /** Print the active mode + the saved profile/rules/task. */
    data object ShowMemory : BranchCommand
    /** Overwrite `profile.md` with the given text (legacy free-text path). */
    data class SetProfile(val text: String) : BranchCommand
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
    /** Touch-create an empty named profile. */
    data class TouchProfile(val name: String) : BranchCommand
    /** Append a bullet to [section] of a named profile. */
    data class AddNamedProfileItem(val name: String, val section: ProfileSection, val text: String) : BranchCommand
    /** Empty a [section] of a named profile. */
    data class ClearNamedProfileSection(val name: String, val section: ProfileSection) : BranchCommand
    /** Delete the named profile file. */
    data class ClearNamedProfile(val name: String) : BranchCommand

    /** Append a new rule under `rules/`. */
    data class AddRule(val text: String) : BranchCommand
    /** Switch the active task id (creates an empty task file if absent). */
    data class SetTask(val taskId: String) : BranchCommand
    /** Append a note to the currently-active task. */
    data class AppendTaskNote(val note: String) : BranchCommand
    /** Pause the active task — hold its stage; auto-advance is suppressed. */
    data object PauseTask : BranchCommand
    /** Resume the active task — clear the pause flag; auto-advance resumes. */
    data object ResumeTask : BranchCommand
    /** Flip the memory-injection mode (PREAMBLE ↔ SYSTEM). */
    data class SetMemoryMode(val mode: MemoryMode) : BranchCommand
}

/**
 * What drives the next user turn at each loop iteration of [SessionLoop].
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
private const val CHECKPOINT_COMMAND = "/checkpoint"
private const val BRANCH_COMMAND = "/branch"
private const val SWITCH_COMMAND = "/switch"
private const val BRANCHES_COMMAND = "/branches"
private const val MEMORY_COMMAND = "/memory"
private const val PROFILE_COMMAND = "/profile"
private const val PROFILE_USE_COMMAND = "/profile-use"
private const val PROFILE_LIST_COMMAND = "/profile-list"
private const val PROFILE_SHOW_COMMAND = "/profile-show"
private const val RULE_COMMAND = "/rule"
private const val TASK_COMMAND = "/task"
private const val TASK_NOTE_COMMAND = "/task-note"
private const val TASK_PAUSE_COMMAND = "/task-pause"
private const val TASK_RESUME_COMMAND = "/task-resume"
private const val MEMORY_MODE_COMMAND = "/memory-mode"
private const val PROMPT_INDICATOR = "> "

/**
 * Reads prompts from an interactive terminal-like reader. Handles the
 * REPL niceties that used to live in [SessionLoop]:
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
                    + "Branches: $BRANCHES_COMMAND, $BRANCH_COMMAND <name>, $SWITCH_COMMAND <name>, $CHECKPOINT_COMMAND.\n"
                    + "Memory: $MEMORY_COMMAND, $PROFILE_LIST_COMMAND, $PROFILE_USE_COMMAND <name>, $PROFILE_SHOW_COMMAND <name>,\n"
                    + "        $PROFILE_COMMAND [<text> | <section> <text> | <section> clear | clear | <name> [<section> [<text>|clear] | clear]],\n"
                    + "        $RULE_COMMAND <text>, $TASK_COMMAND <id>, $TASK_NOTE_COMMAND <text>,\n"
                    + "        $TASK_PAUSE_COMMAND, $TASK_RESUME_COMMAND, $MEMORY_MODE_COMMAND <preamble|system>."
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

/**
 * Classify a `/branch`-family or `/memory`-family command typed at the REPL,
 * or null if [line] isn't one. Top-level so both [StdinPromptSource] and the
 * TUI intent source share one classifier instead of duplicating it. The lone
 * exception is `/memory-mode`, which needs a `preamble` / `system` argument —
 * anything else falls through as a normal prompt (the agent's footer makes
 * the typo obvious; we'd rather not eat the line silently).
 */
internal fun parseSlashCommand(line: String): BranchCommand? {
    val parts = line.split(Regex("\\s+"), limit = 2)
    val arg = parts.getOrNull(1)?.trim().orEmpty()
    return when (parts[0].lowercase()) {
        CHECKPOINT_COMMAND -> BranchCommand.Checkpoint
        BRANCHES_COMMAND -> BranchCommand.ListBranches
        BRANCH_COMMAND -> BranchCommand.Branch(arg)
        SWITCH_COMMAND -> BranchCommand.Switch(arg)
        MEMORY_COMMAND -> BranchCommand.ShowMemory
        PROFILE_COMMAND -> classifyProfileCommand(arg)
        PROFILE_USE_COMMAND -> if (arg.isBlank()) null else BranchCommand.SwitchProfile(arg.trim())
        PROFILE_LIST_COMMAND -> BranchCommand.ListProfiles
        PROFILE_SHOW_COMMAND -> if (arg.isBlank()) null else BranchCommand.ShowProfile(arg.trim())
        RULE_COMMAND -> BranchCommand.AddRule(arg)
        TASK_COMMAND -> BranchCommand.SetTask(arg)
        TASK_NOTE_COMMAND -> BranchCommand.AppendTaskNote(arg)
        TASK_PAUSE_COMMAND -> BranchCommand.PauseTask
        TASK_RESUME_COMMAND -> BranchCommand.ResumeTask
        MEMORY_MODE_COMMAND -> parseMemoryMode(arg)
        else -> null
    }
}

private fun parseMemoryMode(arg: String): BranchCommand? = when (arg.lowercase()) {
    "preamble" -> BranchCommand.SetMemoryMode(MemoryMode.PREAMBLE)
    "system" -> BranchCommand.SetMemoryMode(MemoryMode.SYSTEM)
    else -> null
}

/**
 * Every `/`-command as a palette row — the single source the TUI command
 * palette lists. Names reuse the parser's constants so the palette and
 * [parseSlashCommand] can't drift. Ordered pickers → no-arg → free-text
 * (prefill). `/exit` / `/quit` are omitted (one keystroke away, handled before
 * this), as is the bare prompt.
 */
internal fun commandCatalog(): List<CommandEntry> = listOf(
    CommandEntry(PROFILE_USE_COMMAND, "switch the active named profile", PaletteAction.Pick(PickerKind.Profile)),
    CommandEntry(TASK_COMMAND, "set or switch the active task", PaletteAction.Pick(PickerKind.Task)),
    CommandEntry(BRANCHES_COMMAND, "switch the session branch", PaletteAction.Pick(PickerKind.Branch)),
    CommandEntry(MEMORY_MODE_COMMAND, "switch the memory injection mode", PaletteAction.Pick(PickerKind.MemoryMode)),
    CommandEntry(CHECKPOINT_COMMAND, "show the current branch and message count", PaletteAction.Run(BranchCommand.Checkpoint)),
    CommandEntry(MEMORY_COMMAND, "show the active memory layer", PaletteAction.Run(BranchCommand.ShowMemory)),
    CommandEntry(PROFILE_LIST_COMMAND, "list the named profiles", PaletteAction.Run(BranchCommand.ListProfiles)),
    CommandEntry(TASK_PAUSE_COMMAND, "pause the active task (hold its stage)", PaletteAction.Run(BranchCommand.PauseTask)),
    CommandEntry(TASK_RESUME_COMMAND, "resume the active task", PaletteAction.Run(BranchCommand.ResumeTask)),
    CommandEntry(REUSE_COMMAND, "resend the last model reply", PaletteAction.Reuse),
    CommandEntry(RULE_COMMAND, "add a memory rule", PaletteAction.Prefill("$RULE_COMMAND ")),
    CommandEntry(TASK_NOTE_COMMAND, "append a note to the active task", PaletteAction.Prefill("$TASK_NOTE_COMMAND ")),
    CommandEntry(BRANCH_COMMAND, "fork a new branch from here", PaletteAction.Prefill("$BRANCH_COMMAND ")),
    CommandEntry(PROFILE_COMMAND, "edit a profile section", PaletteAction.Prefill("$PROFILE_COMMAND ")),
    CommandEntry(PROFILE_SHOW_COMMAND, "show a named profile", PaletteAction.Prefill("$PROFILE_SHOW_COMMAND ")),
)

/**
 * Map a `/profile …` body into the matching [BranchCommand].
 *
 * Default-profile shapes (`<section> <text>`, `<section> clear`,
 * `clear`) come from [parseProfileCommand]. Anything else that starts
 * with a valid profile-name identifier is treated as a named-profile
 * sub-command:
 *
 * - `<name>` → touch-create
 * - `<name> clear` → drop the named profile
 * - `<name> <section> <text>` → append to it
 * - `<name> <section> clear` → empty the section
 *
 * Free text (multiple words that don't fit the named shape) drops
 * back to `SetProfile`. Blank input is reported back as
 * `SetProfile("")` so the agent's "needs the new profile text" error
 * path keeps firing.
 */
private fun classifyProfileCommand(arg: String): BranchCommand {
    if (arg.isBlank()) return BranchCommand.SetProfile("")

    when (val parsed = parseProfileCommand(arg)) {
        null -> return BranchCommand.SetProfile("")
        ProfileCommand.ClearAll -> return BranchCommand.ClearProfile
        is ProfileCommand.ClearSection -> return BranchCommand.ClearProfileSection(parsed.section)
        is ProfileCommand.Append -> return BranchCommand.AddProfileItem(parsed.section, parsed.text)
        is ProfileCommand.SetFreeText -> Unit
    }

    val tokens = arg.trim().split(Regex("\\s+"))
    val name = tokens[0]
    if (!isValidProfileName(name)) return BranchCommand.SetProfile(arg.trim())

    if (tokens.size == 1) return BranchCommand.TouchProfile(name)

    val rest = tokens.drop(1)
    if (rest.size == 1 && rest[0].equals("clear", ignoreCase = true)) {
        return BranchCommand.ClearNamedProfile(name)
    }

    val section = ProfileSection.byKeyword(rest[0])
        ?: return BranchCommand.SetProfile(arg.trim())

    if (rest.size == 2 && rest[1].equals("clear", ignoreCase = true)) {
        return BranchCommand.ClearNamedProfileSection(name, section)
    }

    // Drop the leading "<name> <section>" prefix and keep the
    // verbatim remainder as the new bullet (blank → Agent reports
    // "needs text").
    val text = arg.trim().substringAfter(' ').substringAfter(' ').trim()
    return BranchCommand.AddNamedProfileItem(name, section, text)
}

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
 * stops on the next `null` from this source (because [SessionLoop] aborts
 * the feed loop on a failed turn).
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

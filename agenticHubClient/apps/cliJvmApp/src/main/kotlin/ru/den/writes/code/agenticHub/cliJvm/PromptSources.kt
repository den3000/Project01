package ru.den.writes.code.agenticHub.cliJvm

import ru.den.writes.code.agenticHub.features.lifecycle.session.PromptSource
import ru.den.writes.code.agenticHub.features.lifecycle.session.PromptResult
import ru.den.writes.code.agenticHub.cliJvm.commandMappers.CliArgToSessionCommandMapper
import java.io.BufferedReader
import java.io.Reader

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
internal class StdinPromptSource(
    private val reader: BufferedReader,
    private val mapper: CliArgToSessionCommandMapper,
) : PromptSource {

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
            mapper.parse(line)?.let { return PromptResult.Command(it) }
            return PromptResult.Prompt(line)
        }
    }
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

package ru.den.writes.code.project01.cliJvm

import java.io.BufferedReader
import java.io.Reader

/**
 * What drives the next user turn at each loop iteration of [Agent].
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
     * Next user prompt to send, or `null` when the source is exhausted
     * (REPL EOF/quit, file fully consumed, etc.) and the loop should
     * stop.
     */
    fun nextPrompt(): String?

    /**
     * Hook the agent calls after each successful turn. Lets a source
     * cache the latest model reply if it needs to (currently only
     * [StdinPromptSource], for `/reuse`). Default implementation is
     * a no-op — most sources don't care.
     */
    fun observeReply(reply: String) {}

    /**
     * Hook the agent calls when a turn failed (provider returned an
     * error). Default is a no-op — REPL sources just let the user try
     * again. [ChunkedFilePromptSource] uses it to flip an abort flag so
     * the next [nextPrompt] returns `null` and the feed loop stops
     * gracefully instead of feeding more context into an already-broken
     * conversation.
     */
    fun notifyTurnFailed() {}

    /**
     * `true` if this source returned `null` because it was aborted
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
 * REPL niceties that used to live in [Agent]:
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
    private var lastReply: String? = null

    override fun nextPrompt(): String? {
        while (true) {
            println(
                "Type a new prompt and press Enter.\n"
                    + "Type $QUIT_COMMAND or $EXIT_COMMAND to leave.\n"
                    + "Type $REUSE_COMMAND to send prompt above."
            )
            print(PROMPT_INDICATOR)
            System.out.flush()
            val line = reader.readLine()?.trim() ?: return null  // EOF / Ctrl-D
            if (line.isEmpty()) continue
            if (
                line.equals(QUIT_COMMAND, ignoreCase = true)
                || line.equals(EXIT_COMMAND, ignoreCase = true)
            ) return null
            if (line.equals(REUSE_COMMAND, ignoreCase = true)) {
                val cached = lastReply?.takeIf { it.isNotEmpty() } ?: continue
                return cached
            }
            return line
        }
    }

    override fun observeReply(reply: String) {
        lastReply = reply
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
 * Used for the Day-8 overflow demo: point this at a large file with a
 * generous chunk size and the conversation will accumulate context
 * until the model's window can't take any more — at which point the
 * provider returns an error, Agent prints `[error]` and the loop
 * stops on the next `null` from this source (because [Agent] aborts
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

    override fun nextPrompt(): String? {
        if (aborted) return null
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
        if (read == 0) return null
        val chunk = String(buffer, 0, read)
        return if (instruction.isEmpty()) chunk else "$instruction\n\n$chunk"
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

    override fun nextPrompt(): String? {
        if (aborted) return null
        while (true) {
            val line = reader.readLine() ?: return null  // EOF
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue  // skip blank separator lines
            return if (instruction.isEmpty()) trimmed else "$instruction\n\n$trimmed"
        }
    }
}

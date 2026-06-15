package ru.den.writes.code.project01.cliJvm

/**
 * Optional history-compression strategy for [Agent].
 *
 * Keeps the most recent [keepLast] messages verbatim and folds everything
 * older into a single rolling [summaryText]. At request-build time the
 * caller substitutes `[summary] + [recent tail]` for the full history, so
 * prompt tokens stop growing linearly with the conversation. The summary
 * is produced by the session's own model (the [LlmApi] passed to
 * [maybeCompact]); nothing about the wire format or provider DTOs changes.
 *
 * ## Even-length invariant (load-bearing)
 * The message log [Agent] hands us is always **even-length and USER-first**:
 * turns are appended as a (user, assistant) pair and only on success, so
 * between turns the list is `[USER, ASSISTANT, …, USER, ASSISTANT]`. We
 * rely on that to keep Gemini's "alternate user/model, start with user"
 * rule satisfied:
 * - [coveredCount] is always **even**, so `messages.drop(coveredCount)`
 *   begins with a USER message;
 * - the summary is injected as the synthetic pair
 *   `[USER(framed summary), ASSISTANT(ack)]`, so the dropped-in tail (USER
 *   first) alternates after it, and the caller's trailing current-user
 *   turn lands after a trailing ASSISTANT.
 * The empty-tail case ([keepLast] = 0, everything folded) is valid too:
 * `[USER summary][ASSISTANT ack][USER current]`.
 *
 * This class holds mutable summary state but never touches Room — the
 * caller owns persistence (load via [applyPersisted], save after a
 * successful [maybeCompact]).
 *
 * @param keepLast   how many trailing messages to keep verbatim. Snapped
 *   down to an even number (and floored at 0) so the retained tail stays
 *   USER-first/even.
 * @param summarizeEvery batching threshold: only fold once at least this
 *   many messages have piled up beyond the kept tail and the current
 *   watermark, so we don't pay for a summarization on every single turn.
 *   Floored at 2.
 * @param initialSummary previously-persisted rolling summary, if resuming.
 * @param initialCoveredCount previously-persisted watermark, if resuming.
 *   Snapped down to even.
 */
internal class HistoryCompressor(
    keepLast: Int,
    summarizeEvery: Int,
    initialSummary: String? = null,
    initialCoveredCount: Int = 0,
) {
    private val keepLast: Int = evenDown(keepLast)
    private val summarizeEvery: Int = summarizeEvery.coerceAtLeast(2)

    /** Current rolling summary of the folded prefix, or null if nothing folded yet. */
    var summaryText: String? = initialSummary
        private set

    /** Number of leading messages represented by [summaryText]. Always even. */
    var coveredCount: Int = evenDown(initialCoveredCount)
        private set

    /**
     * Build the message list to send for this turn.
     *
     * No summary yet → returns [messages] unchanged (byte-for-byte the
     * un-compressed path). Otherwise returns
     * `[USER(framed summary), ASSISTANT(ack)] + messages.drop(coveredCount)`.
     *
     * Defensive: a watermark past the end of [messages] means stale/corrupt
     * state (e.g. the session was cleaned underneath us) — fall back to the
     * full history rather than emit a truncated/invalid sequence.
     */
    fun planContext(messages: List<Message>): List<Message> {
        val summary = summaryText
        if (summary == null || coveredCount <= 0) return messages
        if (coveredCount > messages.size) return messages
        val covered = evenDown(coveredCount)
        return buildList {
            add(Message(Role.USER, SUMMARY_FRAME_PREFIX + summary))
            add(Message(Role.ASSISTANT, ACK_TEXT))
            addAll(messages.drop(covered))
        }
    }

    /**
     * If at least [summarizeEvery] messages have accumulated beyond the
     * kept tail and the current watermark, summarize that range into an
     * updated rolling summary, advance [coveredCount], and return the
     * outcome. Otherwise — or if the summarization call fails / returns a
     * blank reply — returns null and leaves state untouched (the caller
     * then proceeds with the full tail for this turn and retries next time).
     *
     * The summarization is a single stateless USER turn to [llmApi]; it
     * does not see the synthetic framing pair.
     */
    suspend fun maybeCompact(messages: List<Message>, llmApi: LlmApi): CompactionOutcome? {
        val size = messages.size
        val foldable = size - keepLast - coveredCount
        if (foldable < summarizeEvery) return null

        // Keep exactly `keepLast` verbatim; snap the new watermark to an
        // even boundary so it stays pair-aligned (USER-first tail).
        val newCovered = evenDown(size - keepLast)
        if (newCovered <= coveredCount) return null

        val folded = messages.subList(coveredCount, newCovered).toList()
        val result = llmApi.send(
            messages = listOf(Message(Role.USER, buildSummarizationPrompt(summaryText, folded))),
            params = GenerationParams(maxTokens = SUMMARY_MAX_TOKENS),
        )
        val newSummary = result.text?.takeIf { it.isNotBlank() } ?: return null

        val from = coveredCount
        summaryText = newSummary
        coveredCount = newCovered
        return CompactionOutcome(
            newSummary = newSummary,
            newCoveredCount = newCovered,
            foldedFrom = from,
            foldedTo = newCovered,
            usage = result.usage,
        )
    }

    /**
     * Hydrate state from persistence (called once after the history loads).
     * [coveredCount] is snapped down to even defensively.
     */
    fun applyPersisted(summary: String, coveredCount: Int) {
        summaryText = summary
        this.coveredCount = evenDown(coveredCount)
    }

    companion object {
        /** Prefix on the synthetic USER summary turn, so the model reads it as context. */
        const val SUMMARY_FRAME_PREFIX: String = "[Conversation summary so far]\n"

        /** Synthetic ASSISTANT ack that follows the summary turn to keep roles alternating. */
        const val ACK_TEXT: String = "Understood. I'll use that summary as context for what follows."

        /** Token cap for the summarization call — keeps the rolling summary terse and cheap. */
        const val SUMMARY_MAX_TOKENS: Int = 512

        /**
         * Rolling-summary instruction: integrate the [prior] summary (if
         * any) with the [folded] messages into one replacement summary.
         * Returned as a single string sent as one USER message.
         */
        fun buildSummarizationPrompt(prior: String?, folded: List<Message>): String {
            val rendered = folded.joinToString("\n") { msg ->
                val who = when (msg.role) {
                    Role.USER -> "USER"
                    Role.ASSISTANT -> "ASSISTANT"
                }
                "$who: ${msg.text}"
            }
            return buildString {
                appendLine("You are maintaining a running summary of a conversation so older turns")
                appendLine("can be dropped from the context window while preserving everything needed")
                appendLine("to continue coherently.")
                appendLine()
                if (prior != null) {
                    appendLine("Existing summary so far:")
                    appendLine("\"\"\"")
                    appendLine(prior)
                    appendLine("\"\"\"")
                    appendLine()
                }
                appendLine("New messages to fold in (oldest first):")
                appendLine("\"\"\"")
                appendLine(rendered)
                appendLine("\"\"\"")
                appendLine()
                appendLine("Produce ONE updated summary that REPLACES the existing one:")
                if (prior != null) {
                    appendLine("- integrate the new messages with the existing summary into a single coherent summary;")
                }
                appendLine("- preserve concrete facts, decisions, names, numbers, code identifiers, open")
                appendLine("  questions, user preferences/constraints, and unresolved threads;")
                appendLine("- be concise (prose or terse bullets), no preamble, no meta commentary;")
                appendLine("- do NOT answer or continue the conversation — only summarize.")
                appendLine()
                append("Updated summary:")
            }
        }
    }
}

/**
 * What one successful [HistoryCompressor.maybeCompact] produced — the new
 * rolling summary, the advanced watermark, the folded range `[foldedFrom,
 * foldedTo)`, and the summarizer call's token [usage] (for overhead
 * accounting and the `[compaction]` log line).
 */
internal data class CompactionOutcome(
    val newSummary: String,
    val newCoveredCount: Int,
    val foldedFrom: Int,
    val foldedTo: Int,
    val usage: Usage?,
)

/** Snap a count down to the nearest even number, floored at 0. */
private fun evenDown(n: Int): Int = (if (n < 0) 0 else n).let { it - it % 2 }

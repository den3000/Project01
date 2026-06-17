package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.cliJvm.db.HistoryStore

/**
 * How a session's stored history is shaped into the message list sent to
 * the model each turn. [Agent] owns exactly one strategy and consults it at
 * the single context-assembly seam in `send()`.
 *
 * Three responsibilities, only the first mandatory:
 *  - [planContext] — PURE: turn the full stored history into the wire list.
 *    MUST preserve the even-length / USER-first parity the chat API needs
 *    (see [HistoryCompressor]'s "even-length invariant" note).
 *  - [onTurn] — optional per-turn side effect, run at the TOP of `send()`
 *    (before this turn's user/assistant pair is appended, while the stored
 *    history is still even-length). May call the model, persist, and fold
 *    token spend into session overhead. Default: no-op.
 *  - [rebind] — optional re-hydration of per-(session, branch) state, called
 *    on resume and after a branch switch. Default: no-op.
 *
 * The rolling-summary path lives on as [Summary], wrapping [HistoryCompressor]
 * unchanged. [FullHistory] is the default: send everything, no folding.
 */
internal sealed interface ContextStrategy {
    fun planContext(history: List<Message>): List<Message>
    suspend fun onTurn(ctx: TurnContext) {}
    suspend fun rebind(store: HistoryStore) {}

    /** Send the entire conversation verbatim every turn — the un-compressed default. */
    data object FullHistory : ContextStrategy {
        override fun planContext(history: List<Message>): List<Message> = history
    }

    /**
     * Sliding-window strategy: send only the last [keepLast] messages and
     * drop everything older — no summary, no extra LLM call. The cheapest
     * way to bound prompt growth, at the cost of forgetting old turns
     * outright. [keepLast] is snapped down to an even number so the kept
     * tail stays USER-first and role-alternating; `keepLast = 0` yields an
     * empty tail (the caller still appends the current USER turn).
     */
    class SlidingWindow(keepLast: Int) : ContextStrategy {
        private val keepLast: Int = evenDown(keepLast)
        override fun planContext(history: List<Message>): List<Message> =
            history.takeLast(keepLast)
    }

    /**
     * Rolling-summary strategy: folds old turns into a single
     * summary and sends `[summary pair] + recent tail` instead of the full
     * history. Delegates entirely to [HistoryCompressor] — [onTurn] runs one
     * compaction pass and persists it (the summarization call's tokens land
     * in session overhead, not as a turn); [rebind] reloads the persisted
     * summary so a resumed/switched session keeps folding where it left off.
     */
    class Summary(private val compressor: HistoryCompressor) : ContextStrategy {
        override fun planContext(history: List<Message>): List<Message> =
            compressor.planContext(history)

        override suspend fun onTurn(ctx: TurnContext) {
            val outcome = compressor.maybeCompact(ctx.store.messages, ctx.llmApi) ?: return
            ctx.store.saveSummary(
                summaryText = outcome.newSummary,
                coveredCount = outcome.newCoveredCount,
                modelId = ctx.modelId,
                usage = outcome.usage,
            )
            System.err.println(
                "[compaction] folded messages ${outcome.foldedFrom}..${outcome.foldedTo} " +
                    "into summary; covered=${outcome.newCoveredCount}, " +
                    "kept tail=${ctx.store.messages.size - outcome.newCoveredCount}"
            )
        }

        override suspend fun rebind(store: HistoryStore) {
            store.loadSummary()?.let { compressor.applyPersisted(it.summaryText, it.coveredCount) }
        }
    }
}

/**
 * Everything a [ContextStrategy.onTurn] hook needs for its per-turn side
 * effect: the session [store] (history + persistence), the [llmApi] for any
 * derived call (summary/facts), the current turn's raw [userText], and the
 * [modelId] for overhead cost attribution.
 */
internal class TurnContext(
    val store: HistoryStore,
    val llmApi: LlmApi,
    val userText: String,
    val modelId: String,
)

/**
 * The context-management strategy the user selected (via `-strategy`, or the
 * `-compress` shorthand). The parser produces this intent; `main` maps it to a
 * concrete [ContextStrategy], wiring in runtime dependencies (the compressor,
 * the window size, …) that don't belong in parsed CLI args.
 */
internal enum class ContextStrategyKind { FULL, WINDOW, FACTS, SUMMARY }

/**
 * Snap a count down to the nearest even number, floored at 0. Shared by the
 * strategies that keep a USER-first / even tail ([ContextStrategy.SlidingWindow]
 * and [HistoryCompressor]), both of which rely on the parity invariant.
 */
internal fun evenDown(n: Int): Int = (if (n < 0) 0 else n).let { it - it % 2 }

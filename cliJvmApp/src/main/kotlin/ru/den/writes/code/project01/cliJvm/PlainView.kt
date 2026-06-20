package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.den.writes.code.project01.shared.pricing.PricingRegistry

/** The 72-char `<<<…` footer rule. */
private const val FOOTER_RULE_WIDTH = 72

/** Threshold (% of context window) above which the footer warns about overflow. */
private const val CONTEXT_WARN_PCT: Double = 90.0

/**
 * Plain renderer of [UiState]: reproduces the previous `SessionLoop` output
 * byte-for-byte. Reply + footer → stdout; `[session]` / `[task]` / `[warning]`
 * / `[error]` / `[branch]` / `[memory]` → stderr (the split a user relies on
 * when redirecting a transcript). The default view for feed / oneshot /
 * non-TTY.
 *
 * It observes the view-model's [StateFlow] and prints each newly-appended
 * [UiLine] once, in order. [multiAgent] mirrors `routedAgents.isNotEmpty()` —
 * only multi-agent sessions print the `[[AGENT:]]` tag.
 */
internal class PlainView(private val multiAgent: Boolean) {
    private var printed = 0

    /**
     * Drive [vm] over [primary] (and optional [followUp]) while printing state
     * deltas. Returns when the session ends. A conflating [StateFlow] may skip
     * intermediate states, so a final flush prints whatever the collector
     * missed — line order within each stream is preserved either way.
     */
    suspend fun run(
        vm: SessionViewModel,
        primary: IntentSource,
        followUp: IntentSource? = null,
    ): Unit = coroutineScope {
        val collector = launch { vm.state.collect { flush(it) } }
        try {
            vm.run(primary, followUp)
        } finally {
            collector.cancel()
            collector.join()
            flush(vm.state.value)
        }
    }

    private fun flush(state: UiState) {
        state.lines.drop(printed).forEach(::render)
        printed = state.lines.size
    }

    private fun render(line: UiLine) = when (line) {
        // The terminal already echoed the typed line; re-printing would double it.
        is UiLine.User -> Unit
        is UiLine.Turn -> renderTurn(line.outcome)
        is UiLine.Error -> System.err.println("[error] ${line.reason}")
        is UiLine.Notice -> System.err.println(line.text)
    }

    private fun renderTurn(o: TurnResult.Ok) {
        if (multiAgent) println(agentTag(o.profileName, o.modelId))
        println(o.reply)
        printFooter(o)
        renderStageAdvance(o.stageAdvance)
    }

    private fun printFooter(o: TurnResult.Ok) {
        val rule = "<".repeat(FOOTER_RULE_WIDTH)
        val pricing = PricingRegistry.lookup(o.modelId)
        println(rule)
        println("duration: ${o.durationMs} ms")
        val usage = o.usage
        if (usage != null) {
            val cost = pricing?.let { PricingRegistry.cost(usage, it) }
            println("turn:    " + formatTurnTokens(usage) + "  cost=${formatCost(cost, pricing != null)}")
            pricing?.contextWindowTokens?.let { window ->
                println(formatContextFill(usage.promptTokens, window))
            }
            o.session?.let { s ->
                val sessionCost = if (pricing != null) s.costUsd else null
                println(
                    "session: turns=${s.turns} " + formatSessionTokens(s) +
                        "  cost=${formatCost(sessionCost, pricing != null)}"
                )
            }
        }
        println(rule)
        // Warn loudly near the limit — on stderr so it survives stdout redirection.
        val window = pricing?.contextWindowTokens
        if (usage != null && window != null) {
            val pct = usage.promptTokens.toDouble() / window * 100.0
            if (pct >= CONTEXT_WARN_PCT) {
                System.err.println("[warning] context window %.1f%% full — next turn may overflow".format(pct))
            }
        }
    }

    private fun renderStageAdvance(advance: StageAdvance) = when (advance) {
        StageAdvance.None -> Unit
        is StageAdvance.Advanced ->
            System.err.println("[task] stage: ${advance.from?.keyword ?: "(none)"} → ${advance.to.keyword} (auto)")
        is StageAdvance.Rejected ->
            System.err.println(
                "[task] model proposed ${advance.from?.keyword ?: "(none)"} → ${advance.proposed.keyword}, " +
                    "not allowed (allowed: ${advance.allowed.joinToString(", ") { it.keyword }}) — ignored"
            )
    }
}

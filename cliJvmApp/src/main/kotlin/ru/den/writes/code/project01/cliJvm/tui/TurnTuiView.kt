package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope
import ru.den.writes.code.project01.cliJvm.SessionStatsSnapshot
import ru.den.writes.code.project01.cliJvm.formatCost
import ru.den.writes.code.project01.cliJvm.formatTurnTokens
import ru.den.writes.code.project01.shared.llm.Usage
import ru.den.writes.code.project01.shared.pricing.PricingRegistry

/** Per-turn footer in the transcript: `turn N │ prompt=… output=… total=… cost=…`. */
internal data class TurnTuiView(
    val usage: Usage?,
    val modelId: String,
    val session: SessionStatsSnapshot?,
) : TuiView {
    override fun RenderScope.render(terminal: Terminal, width: Int) {
        val u = usage ?: return
        val pricing = PricingRegistry.lookup(modelId)
        val cost = pricing?.let { PricingRegistry.cost(u, it) }
        val line = formatTurnTokens(u) + "  cost=${formatCost(cost, pricing != null)}"
        wrapWords("turn ${session?.turns ?: 1}", line, width).forEach { textLine(it) }
    }
}

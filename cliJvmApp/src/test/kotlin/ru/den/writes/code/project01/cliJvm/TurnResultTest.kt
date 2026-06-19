package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.shared.llm.Usage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SessionStats.snapshot] immutability + the footer formatters that PlainView
 * will reuse. The formatters' `cost=$X` / `thoughts=` branches aren't reached
 * by [ru.den.writes.code.project01.cliJvm.agent.PlainViewGoldenTest] (it runs
 * off-registry models on purpose), so they're pinned directly here.
 */
class TurnResultTest {

    //region snapshot is immutable

    @Test
    fun `when stats mutate after a snapshot - then the snapshot is unchanged`() {
        // given
        val stats = SessionStats()
        stats.record(Usage(10, 5, 0, 15), costUsd = 0.5)
        val snap = stats.snapshot()

        // when — a later turn folds in more usage
        stats.record(Usage(20, 10, 0, 30), costUsd = 1.0)

        // then — the earlier snapshot still reflects the first turn only
        assertEquals(1, snap.turns)
        assertEquals(10, snap.promptTokens)
        assertEquals(5, snap.outputTokens)
        assertEquals(15, snap.totalTokens)
        assertEquals(0.5, snap.costUsd)
    }
    //endregion

    //region formatCost

    @Test
    fun `when pricing known and cost present - then five-decimal dollar amount`() {
        // when - then
        assertEquals("\$0.00125", formatCost(0.00125, knownPricing = true))
    }

    @Test
    fun `when pricing unknown - then no-pricing placeholder`() {
        // when - then
        assertEquals("\$? (no pricing)", formatCost(null, knownPricing = false))
    }

    @Test
    fun `when cost null despite known pricing - then no-pricing placeholder`() {
        // when - then
        assertEquals("\$? (no pricing)", formatCost(null, knownPricing = true))
    }
    //endregion

    //region formatTurnTokens

    @Test
    fun `when no thoughts tokens - then prompt output total only`() {
        // when - then
        assertEquals("prompt=10  output=5  total=15", formatTurnTokens(Usage(10, 5, 0, 15)))
    }

    @Test
    fun `when thoughts tokens present - then included between output and total`() {
        // when - then
        assertEquals(
            "prompt=100  output=20  thoughts=30  total=150",
            formatTurnTokens(Usage(100, 20, 30, 150)),
        )
    }
    //endregion
}

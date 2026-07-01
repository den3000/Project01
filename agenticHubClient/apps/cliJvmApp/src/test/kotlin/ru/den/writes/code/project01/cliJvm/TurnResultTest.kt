package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.shared.llm.Usage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SessionStats.snapshot] immutability — a snapshot taken mid-session must not
 * change when later turns fold more usage into the live [SessionStats]. (The
 * footer/token/cost formatting now lives in the per-line views and is pinned by
 * their own tests, e.g. `plain/TurnPlainViewTest`.)
 */
class TurnResultTest {

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
}

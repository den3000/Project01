package ru.den.writes.code.project01.cliJvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shape checks for the MVI state container the view-model will drive. */
class UiStateTest {

    @Test
    fun `when default-constructed - then empty idle state`() {
        // when
        val state = UiState()

        // then
        assertTrue(state.lines.isEmpty())
        assertFalse(state.busy)
        assertNull(state.stats)
    }

    @Test
    fun `when a line is appended via copy - then prior lines are preserved in order`() {
        // given
        val state = UiState(lines = listOf(UiLine.Notice("[session] resumed")))

        // when
        val next = state.copy(lines = state.lines + UiLine.Error("boom"))

        // then
        assertEquals(
            listOf(UiLine.Notice("[session] resumed"), UiLine.Error("boom")),
            next.lines,
        )
    }
}

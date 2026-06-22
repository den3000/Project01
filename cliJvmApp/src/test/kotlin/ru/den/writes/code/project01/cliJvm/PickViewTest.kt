package ru.den.writes.code.project01.cliJvm

import kotlin.test.Test
import kotlin.test.assertEquals

/** The TUI gate: opt-in flag AND a real TTY, else plain. */
class PickViewTest {

    @Test
    fun `when tui requested on a TTY - then TUI`() {
        // when - then
        assertEquals(ViewKind.TUI, pickView(tui = true, hasConsole = true))
    }

    @Test
    fun `when tui requested without a TTY - then plain`() {
        // when - then
        assertEquals(ViewKind.PLAIN, pickView(tui = true, hasConsole = false))
    }

    @Test
    fun `when tui not requested - then plain regardless of TTY`() {
        // when - then
        assertEquals(ViewKind.PLAIN, pickView(tui = false, hasConsole = true))
        assertEquals(ViewKind.PLAIN, pickView(tui = false, hasConsole = false))
    }
}

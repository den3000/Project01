package ru.den.writes.code.project01.cliJvm.tui

import ru.den.writes.code.project01.cliJvm.BranchCommand
import ru.den.writes.code.project01.cliJvm.UiIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure pieces of the TUI view — word-wrap and input classification. The
 * Kotter `session` / `aside` / `section` machinery needs a real terminal and
 * is left to a manual live run.
 */
class TuiViewTest {

    //region wrapWords

    @Test
    fun `when text fits the width - then a single prefixed line`() {
        // when - then
        assertEquals(listOf("assistant │ short reply"), wrapWords("assistant", "short reply", width = 80))
    }

    @Test
    fun `when text exceeds the width - then continuations align under the bar`() {
        // when
        val out = wrapWords("assistant", "one two three four five", width = 24)

        // then — prefix is 12 chars; continuations indent 12 spaces under the │
        assertTrue(out.size > 1, "expected a wrap, got $out")
        assertTrue(out.first().startsWith("assistant │ "))
        assertTrue(out.drop(1).all { it.startsWith(" ".repeat(12)) }, "continuations should be indented: $out")
    }

    @Test
    fun `when text is empty - then just the prefix`() {
        // when - then
        assertEquals(listOf("assistant │ "), wrapWords("assistant", "", width = 80))
    }
    //endregion

    //region toIntent

    @Test
    fun `when blank - then null`() {
        // when - then
        assertNull(toIntent(""))
    }

    @Test
    fun `when exit or quit - then Exit`() {
        // when - then
        assertEquals(UiIntent.Exit, toIntent("/exit"))
        assertEquals(UiIntent.Exit, toIntent("/QUIT"))
    }

    @Test
    fun `when reuse - then Reuse`() {
        // when - then
        assertEquals(UiIntent.Reuse, toIntent("/reuse"))
    }

    @Test
    fun `when a slash command - then SlashCommand`() {
        // when - then
        assertEquals(UiIntent.SlashCommand(BranchCommand.Branch("exp")), toIntent("/branch exp"))
    }

    @Test
    fun `when plain text - then Submit`() {
        // when - then
        assertEquals(UiIntent.Submit("hello there"), toIntent("hello there"))
    }
    //endregion
}

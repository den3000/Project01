package ru.den.writes.code.project01.cliJvm.tui

import ru.den.writes.code.project01.cliJvm.BranchCommand
import ru.den.writes.code.project01.cliJvm.PickerKind
import ru.den.writes.code.project01.cliJvm.PickerState
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

    /** `wrapWords` is a default method on the sealed [TuiView]; call it off any variant. */
    private val wrap: TuiView = UserTuiView("")

    //region wrapWords

    @Test
    fun `when text fits the width - then a single prefixed line`() {
        // when - then
        assertEquals(listOf("assistant │ short reply"), wrap.wrapWords("assistant", "short reply", width = 80))
    }

    @Test
    fun `when text exceeds the width - then continuations align under the bar`() {
        // when
        val out = wrap.wrapWords("assistant", "one two three four five", width = 24)

        // then — prefix is 12 chars; continuations indent 12 spaces under the │
        assertTrue(out.size > 1, "expected a wrap, got $out")
        assertTrue(out.first().startsWith("assistant │ "))
        assertTrue(out.drop(1).all { it.startsWith(" ".repeat(10) + "│ ") }, "continuations align under the bar: $out")
    }

    @Test
    fun `when text is empty - then just the prefix`() {
        // when - then
        assertEquals(listOf("assistant │ "), wrap.wrapWords("assistant", "", width = 80))
    }

    @Test
    fun `when text has explicit newlines - then they are preserved with aligned continuations`() {
        // when — a markdown-style list keeps its line breaks
        val out = wrap.wrapWords("assistant", "line one\nline two", width = 80)

        // then
        assertEquals(listOf("assistant │ line one", "          │ line two"), out)
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

    @Test
    fun `when an argument-less picker command - then OpenPicker of that kind`() {
        // when - then
        assertEquals(UiIntent.OpenPicker(PickerKind.Profile), toIntent("/profile-use"))
        assertEquals(UiIntent.OpenPicker(PickerKind.Task), toIntent("/task"))
        assertEquals(UiIntent.OpenPicker(PickerKind.Branch), toIntent("/switch"))
        assertEquals(UiIntent.OpenPicker(PickerKind.MemoryMode), toIntent("/memory-mode"))
    }

    @Test
    fun `when a picker command carries an argument - then it stays a SlashCommand`() {
        // when - then — the argument form is untouched, only the bare form opens a picker
        assertEquals(UiIntent.SlashCommand(BranchCommand.SwitchProfile("work")), toIntent("/profile-use work"))
    }
    //endregion

    //region PickerTuiView

    @Test
    fun `when rendering options - then rows are numbered and the cursor is marked`() {
        // given
        val view = PickerTuiView(PickerState(PickerKind.Profile, listOf("home", "work"), cursor = 1))

        // when - then
        assertEquals(listOf("  1. home", "▶ 2. work"), view.optionLines())
    }
    //endregion
}

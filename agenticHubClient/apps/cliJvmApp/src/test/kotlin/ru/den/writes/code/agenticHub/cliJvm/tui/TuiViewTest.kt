package ru.den.writes.code.agenticHub.cliJvm.tui

import ru.den.writes.code.agenticHub.cliJvm.agent.testSessionMapper

import ru.den.writes.code.agenticHub.features.lifecycle.session.SessionCommand
import ru.den.writes.code.agenticHub.features.lifecycle.session.CommandEntry
import ru.den.writes.code.agenticHub.features.lifecycle.session.Overlay
import ru.den.writes.code.agenticHub.features.lifecycle.session.PaletteAction
import ru.den.writes.code.agenticHub.features.lifecycle.session.PickerKind
import ru.den.writes.code.agenticHub.features.lifecycle.session.SessionStatsSnapshot
import ru.den.writes.code.agenticHub.features.lifecycle.session.UiIntent
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
        assertNull(toIntent("", testSessionMapper))
    }

    @Test
    fun `when exit or quit - then Exit`() {
        // when - then
        assertEquals(UiIntent.Exit, toIntent("/exit", testSessionMapper))
        assertEquals(UiIntent.Exit, toIntent("/QUIT", testSessionMapper))
    }

    @Test
    fun `when reuse - then Reuse`() {
        // when - then
        assertEquals(UiIntent.Reuse, toIntent("/reuse", testSessionMapper))
    }

    @Test
    fun `when a slash command - then the SessionCommand intent`() {
        // when - then
        assertEquals(SessionCommand.Branch("exp"), toIntent("/branch exp", testSessionMapper))
    }

    @Test
    fun `when plain text - then Submit`() {
        // when - then
        assertEquals(UiIntent.Submit("hello there"), toIntent("hello there", testSessionMapper))
    }

    @Test
    fun `when an argument-less picker command - then OpenPicker of that kind`() {
        // when - then
        assertEquals(UiIntent.OpenPicker(PickerKind.Profile), toIntent("/profile", testSessionMapper))
        assertEquals(UiIntent.OpenPicker(PickerKind.Task), toIntent("/task", testSessionMapper))
        assertEquals(UiIntent.OpenPicker(PickerKind.Branch), toIntent("/branch", testSessionMapper))
        assertEquals(UiIntent.OpenPicker(PickerKind.MemoryMode), toIntent("/agent mode", testSessionMapper))
    }

    @Test
    fun `when a picker command carries an argument - then it stays a SessionCommand`() {
        // when - then — the argument form is untouched, only the bare form opens a picker
        assertEquals(SessionCommand.SwitchProfile("work"), toIntent("/profile work", testSessionMapper))
    }

    @Test
    fun `when help or question mark - then OpenPalette`() {
        // when - then
        assertEquals(UiIntent.OpenPalette, toIntent("/help", testSessionMapper))
        assertEquals(UiIntent.OpenPalette, toIntent("/?", testSessionMapper))
    }
    //endregion

    //region PickerTuiView

    @Test
    fun `when rendering options - then rows are numbered and the cursor is marked`() {
        // given
        val view = PickerTuiView(Overlay.Picker(PickerKind.Profile, listOf("home", "work"), cursor = 1))

        // when - then
        assertEquals(listOf("  1. home", "▶ 2. work"), view.optionLines())
    }
    //endregion

    //region PaletteTuiView

    @Test
    fun `when rendering palette rows - then each shows name and help with the cursor marked`() {
        // given
        val palette = Overlay.Palette(
            listOf(
                CommandEntry("/branch show", "show the branch", PaletteAction.Run(SessionCommand.Checkpoint)),
                CommandEntry("/rule", "add a rule", PaletteAction.Prefill("/rule ")),
            ),
            cursor = 0,
        )

        // when - then
        assertEquals(
            listOf("▶ 1. /branch show — show the branch", "  2. /rule — add a rule"),
            PaletteTuiView(palette).optionLines(),
        )
    }
    //endregion

    //region SessionPanelTuiView

    @Test
    fun `when the session panel renders - then its width is fixed regardless of the numbers`() {
        // given — a tiny and a huge stats snapshot
        val small = SessionPanelTuiView(
            SessionStatsSnapshot(1, promptTokens = 5, outputTokens = 5, thoughtsTokens = 0, totalTokens = 10, costUsd = 0.0),
        )
        val big = SessionPanelTuiView(
            SessionStatsSnapshot(999, promptTokens = 9_999_999, outputTokens = 999_999, thoughtsTokens = 0, totalTokens = 9_999_999, costUsd = 1234.56789),
        )

        // when — rendered at the same width
        val smallTop = small.panelLines(60).first()
        val bigTop = big.panelLines(60).first()

        // then — the box is the SAME (fixed) width both times: no resize → no leaked top border
        assertEquals(60, smallTop.length)
        assertEquals(smallTop.length, bigTop.length)
    }
    //endregion
}

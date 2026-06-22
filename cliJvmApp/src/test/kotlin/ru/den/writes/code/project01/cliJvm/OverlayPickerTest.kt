package ru.den.writes.code.project01.cliJvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure navigation / selection logic of [Overlay.Picker] — no terminal, no
 * view-model. The cursor wraps both ways; selection maps the input box to a row
 * (empty → the cursor, a 1-based number → that row, anything else → null).
 */
class OverlayPickerTest {

    private val picker = Overlay.Picker(PickerKind.Profile, listOf("a", "b", "c"), cursor = 0)

    //region moved

    @Test
    fun `when moved down within bounds - then the cursor advances`() {
        // when - then
        assertEquals(1, picker.moved(+1).cursor)
    }

    @Test
    fun `when moved up from the first row - then the cursor wraps to the last`() {
        // when - then
        assertEquals(2, picker.moved(-1).cursor)
    }

    @Test
    fun `when moved down from the last row - then the cursor wraps to the first`() {
        // given
        val atEnd = picker.copy(cursor = 2)

        // when - then
        assertEquals(0, atEnd.moved(+1).cursor)
    }

    @Test
    fun `when moved on an empty list - then it stays put`() {
        // given
        val empty = Overlay.Picker(PickerKind.Task, emptyList())

        // when - then
        assertEquals(empty, empty.moved(+1))
    }
    //endregion

    //region selectionIndex

    @Test
    fun `when the input is empty - then the cursor row is selected`() {
        // given
        val atSecond = picker.copy(cursor = 1)

        // when - then
        assertEquals(1, atSecond.selectionIndex(""))
    }

    @Test
    fun `when the input is a valid 1-based number - then its zero-based index`() {
        // when - then
        assertEquals(2, picker.selectionIndex("3"))
    }

    @Test
    fun `when the input number is out of range - then null`() {
        // when - then
        assertNull(picker.selectionIndex("9"))
        assertNull(picker.selectionIndex("0"))
    }

    @Test
    fun `when the input is not a number - then null`() {
        // when - then
        assertNull(picker.selectionIndex("xyz"))
    }
    //endregion
}

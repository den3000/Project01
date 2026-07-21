package ru.den.writes.code.agenticHub.mcps.projectfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectTextTest {

    //region counting lines
    @Test
    fun `when the text ends with a newline - then that newline terminates the last line`() {
        // given
        val text = "a\nb\n"

        // when - then
        assertEquals(2, text.countLines())
        assertEquals(listOf("a", "b"), text.toDisplayLines())
    }

    @Test
    fun `when the text does not end with a newline - then the tail still counts as a line`() {
        // given
        val text = "a\nb"

        // when - then
        assertEquals(2, text.countLines())
        assertEquals(listOf("a", "b"), text.toDisplayLines())
    }

    @Test
    fun `when the text is empty - then it holds no lines at all`() {
        // given
        val text = ""

        // when - then
        assertEquals(0, text.countLines())
        assertEquals(emptyList(), text.toDisplayLines())
    }
    //endregion

    //region clipping and counting occurrences
    @Test
    fun `when a line fits the display width - then it is left alone`() {
        // given
        val line = "x".repeat(MAX_LINE_CHARS)

        // when
        val actual = line.clipLine()

        // then
        assertEquals(line, actual)
    }

    @Test
    fun `when a line is over the display width - then it is cut and says how much was dropped`() {
        // given
        val line = "x".repeat(MAX_LINE_CHARS + 7)

        // when
        val actual = line.clipLine()

        // then
        assertEquals("x".repeat(MAX_LINE_CHARS) + "…(+7)", actual)
    }

    @Test
    fun `when occurrences overlap - then only the non-overlapping ones are counted`() {
        // given
        val text = "aaaa"

        // when
        val actual = text.countOccurrences("aa")

        // then
        assertEquals(2, actual)
    }

    @Test
    fun `when the needle is empty - then nothing is counted`() {
        // given
        val text = "anything"

        // when
        val actual = text.countOccurrences("")

        // then
        assertEquals(0, actual)
    }
    //endregion

    //region stripping line-number gutters
    @Test
    fun `when every line carries a gutter - then all of them are stripped`() {
        // given
        val quoted = " 9| first\n10| second"

        // when
        val actual = quoted.stripLineNumbers()

        // then
        assertEquals("first\nsecond", actual)
    }

    @Test
    fun `when a blank line sits among numbered ones - then stripping still applies`() {
        // given
        val quoted = "1| first\n\n3| third"

        // when
        val actual = quoted.stripLineNumbers()

        // then
        assertEquals("first\n\nthird", actual)
    }

    @Test
    fun `when only one line looks numbered - then the text is left untouched`() {
        // given
        val genuine = "смотри пример:\n42| foo\nконец"

        // when
        val actual = genuine.stripLineNumbers()

        // then
        assertEquals(genuine, actual)
    }

    @Test
    fun `when no line carries a gutter - then the text is left untouched`() {
        // given
        val plain = "first\nsecond"

        // when
        val actual = plain.stripLineNumbers()

        // then
        assertEquals(plain, actual)
    }
    //endregion

    //region sniffing binaries
    @Test
    fun `when the text holds a NUL early on - then it reads as binary`() {
        // given
        val text = "PNG" + Char(0) + "data"

        // when - then
        assertTrue(text.looksBinary())
    }

    @Test
    fun `when the text is ordinary - then it does not read as binary`() {
        // given
        val text = "обычный текст\nвторая строка\n"

        // when - then
        assertFalse(text.looksBinary())
    }
    //endregion
}

package ru.den.writes.code.agenticHub.mcps.projectfs

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProjectReaderTest {

    //region the window
    @Test
    fun `when a short file is read - then every line comes back numbered`() {
        // given
        val reader = projectReader(files = mapOf("a.md" to "first\nsecond\n"))

        // when
        val actual = reader.read("a.md")

        // then
        assertEquals("a.md (lines 1-2 of 2)\n1| first\n2| second", actual)
    }

    @Test
    fun `when a line is blank - then its gutter carries no trailing space`() {
        // given
        val reader = projectReader(files = mapOf("a.md" to "first\n\nthird\n"))

        // when
        val actual = reader.read("a.md")

        // then
        assertEquals("a.md (lines 1-3 of 3)\n1| first\n2|\n3| third", actual)
    }

    @Test
    fun `when an offset and a limit are given - then only that window is returned`() {
        // given
        val reader = projectReader(files = mapOf("a.md" to (1..10).joinToString("\n") { "line$it" }))

        // when
        val actual = reader.read("a.md", offset = 4, limit = 2)

        // then
        val expected = "a.md (lines 4-5 of 10)\n4| line4\n5| line5\n" +
            "… файл длиннее: продолжить read_project_file(path=\"a.md\", offset=6, limit=2)"
        assertEquals(expected, actual)
    }

    @Test
    fun `when the window stops short of the end - then the footer spells out the next call`() {
        // given
        val reader = projectReader(files = mapOf("a.md" to (1..10).joinToString("\n") { "line$it" }))

        // when
        val actual = reader.read("a.md", limit = 3)

        // then
        assertContains(actual, "продолжить read_project_file(path=\"a.md\", offset=4, limit=3)")
    }

    @Test
    fun `when the window reaches the end - then no continuation is offered`() {
        // given
        val reader = projectReader(files = mapOf("a.md" to "only\n"))

        // when
        val actual = reader.read("a.md")

        // then
        assertFalse(actual.contains("продолжить"), "получено: $actual")
    }

    @Test
    fun `when a line is longer than the display width - then it is clipped with a marker`() {
        // given
        val long = "x".repeat(MAX_LINE_CHARS + 5)
        val reader = projectReader(files = mapOf("a.md" to long))

        // when
        val actual = reader.read("a.md")

        // then
        assertContains(actual, "…(+5)")
    }
    //endregion

    //region clamping
    @Test
    fun `when a limit above the ceiling is given - then it is clamped to the ceiling`() {
        // given
        val reader = projectReader(files = mapOf("a.md" to (1..READ_LIMIT_MAX + 50).joinToString("\n") { "line$it" }))

        // when
        val actual = reader.read("a.md", limit = READ_LIMIT_MAX * 10)

        // then
        assertContains(actual, "(lines 1-$READ_LIMIT_MAX of ${READ_LIMIT_MAX + 50})")
    }

    @Test
    fun `when a limit below one is given - then a single line is returned`() {
        // given
        val reader = projectReader(files = mapOf("a.md" to "first\nsecond\n"))

        // when
        val actual = reader.read("a.md", limit = 0)

        // then
        assertContains(actual, "(lines 1-1 of 2)")
    }

    @Test
    fun `when an offset below one is given - then reading starts at the first line`() {
        // given
        val reader = projectReader(files = mapOf("a.md" to "first\nsecond\n"))

        // when
        val actual = reader.read("a.md", offset = -5)

        // then
        assertContains(actual, "(lines 1-2 of 2)")
    }
    //endregion

    //region refusals
    @Test
    fun `when the offset is past the end - then the error names the real line count`() {
        // given
        val reader = projectReader(files = mapOf("a.md" to "first\nsecond\n"))

        // when
        val actual = reader.read("a.md", offset = 99)

        // then
        assertEquals("projectfs error: в 'a.md' 2 стр., offset=99 за пределами", actual)
    }

    @Test
    fun `when the file is past the large threshold - then the error points at search`() {
        // given
        val reader = projectReader(
            files = mapOf("dump.sql" to "irrelevant"),
            sizes = mapOf("dump.sql" to 2 * LARGE_FILE_BYTES),
        )

        // when
        val actual = reader.read("dump.sql")

        // then
        assertContains(actual, "слишком большой")
        assertContains(actual, "search_project_files")
    }

    @Test
    fun `when the file sniffs as binary - then it is refused`() {
        // given
        val reader = projectReader(files = mapOf("logo.dat" to "PNG" + Char(0) + "data"))

        // when
        val actual = reader.read("logo.dat")

        // then
        assertContains(actual, "выглядит бинарным")
    }

    @Test
    fun `when the file is absent - then it is reported as unreadable`() {
        // given
        val reader = projectReader(files = mapOf("a.md" to "text"))

        // when
        val actual = reader.read("missing.md")

        // then
        assertEquals("projectfs error: 'missing.md' не найден или нечитаем", actual)
    }

    @Test
    fun `when the file is empty - then that is stated rather than an empty window`() {
        // given
        val reader = projectReader(files = mapOf("a.md" to ""))

        // when
        val actual = reader.read("a.md")

        // then
        assertEquals("a.md (пустой файл)", actual)
    }

    @Test
    fun `when the path is closed by the gate - then the gate's reason is passed through`() {
        // given
        val reader = projectReader(files = mapOf("local.properties" to "key=secret"))

        // when
        val actual = reader.read("local.properties")

        // then
        assertContains(actual, "путь закрыт")
    }
    //endregion
}

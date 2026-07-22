package ru.den.writes.code.agenticHub.mcps.projectfs

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class UnifiedDiffTest {

    //region degenerate cases
    @Test
    fun `when the two sides are identical - then no diff is rendered`() {
        // given
        val text = "one\ntwo\n"

        // when
        val actual = unifiedDiff("a.md", text, text)

        // then
        assertEquals("(без изменений)", actual)
    }

    @Test
    fun `when only the trailing newline moved - then that is said instead of a hunk`() {
        // given
        val before = "one\ntwo"
        val after = "one\ntwo\n"

        // when
        val actual = unifiedDiff("a.md", before, after)

        // then
        assertEquals("(изменился только перевод строки в конце файла)", actual)
    }

    @Test
    fun `when the file did not exist - then the old side is dev null`() {
        // given
        val after = "fresh\n"

        // when
        val actual = unifiedDiff("report.md", before = "", after = after)

        // then
        assertEquals("--- /dev/null\n+++ b/report.md\n@@ -0,0 +1,1 @@\n+fresh", actual)
    }
    //endregion

    //region hunks
    @Test
    fun `when a single line is replaced - then the hunk shows it removed and added`() {
        // given
        val before = "one\ntwo\nthree\n"
        val after = "one\nTWO\nthree\n"

        // when
        val actual = unifiedDiff("a.md", before, after)

        // then
        val expected = "--- a/a.md\n+++ b/a.md\n@@ -1,3 +1,3 @@\n one\n-two\n+TWO\n three"
        assertEquals(expected, actual)
    }

    @Test
    fun `when a line is only added - then the hunk carries no deletion`() {
        // given
        val before = "one\ntwo\n"
        val after = "one\ninserted\ntwo\n"

        // when
        val actual = unifiedDiff("a.md", before, after)

        // then
        assertEquals("--- a/a.md\n+++ b/a.md\n@@ -1,2 +1,3 @@\n one\n+inserted\n two", actual)
    }

    @Test
    fun `when a line is only removed - then the hunk carries no addition`() {
        // given
        val before = "one\ndoomed\ntwo\n"
        val after = "one\ntwo\n"

        // when
        val actual = unifiedDiff("a.md", before, after)

        // then
        assertEquals("--- a/a.md\n+++ b/a.md\n@@ -1,3 +1,2 @@\n one\n-doomed\n two", actual)
    }

    @Test
    fun `when a change sits in a long file - then only its context is shown`() {
        // given
        val before = (1..20).joinToString("\n") { "line$it" }
        val after = before.replace("line10", "CHANGED")

        // when
        val actual = unifiedDiff("a.md", before, after, context = 1)

        // then
        assertEquals("--- a/a.md\n+++ b/a.md\n@@ -9,3 +9,3 @@\n line9\n-line10\n+CHANGED\n line11", actual)
    }

    @Test
    fun `when two changes are far apart - then they render as separate hunks`() {
        // given
        val before = (1..20).joinToString("\n") { "line$it" }
        val after = before.replace("line2", "A").replace("line18", "B")

        // when
        val actual = unifiedDiff("a.md", before, after, context = 1)

        // then
        assertEquals(2, actual.lines().count { it.startsWith("@@") })
    }

    @Test
    fun `when two changes are adjacent - then their hunks are merged into one`() {
        // given
        val before = (1..20).joinToString("\n") { "line$it" }
        val after = before.replace("line10", "A").replace("line11", "B")

        // when
        val actual = unifiedDiff("a.md", before, after, context = 3)

        // then
        assertEquals(1, actual.lines().count { it.startsWith("@@") })
    }

    @Test
    fun `when the rendered diff is longer than the cap - then it is clipped with a notice`() {
        // given
        val before = (1..50).joinToString("\n") { "line$it" }
        val after = (1..50).joinToString("\n") { "CHANGED$it" }

        // when
        val actual = unifiedDiff("a.md", before, after, maxLines = 10)

        // then
        assertContains(actual, "… (diff обрезан: 10 из")
    }
    //endregion

    //region diffLines
    @Test
    fun `when the sides share a prefix and a suffix - then only the middle is diffed`() {
        // given
        val before = listOf("a", "b", "c")
        val after = listOf("a", "B", "c")

        // when
        val actual = diffLines(before, after)

        // then
        val expected = listOf(
            DiffLine(Op.KEEP, "a"),
            DiffLine(Op.DEL, "b"),
            DiffLine(Op.ADD, "B"),
            DiffLine(Op.KEEP, "c"),
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `when the before side is empty - then every line reads as added`() {
        // given
        val after = listOf("x", "y")

        // when
        val actual = diffLines(emptyList(), after)

        // then
        assertEquals(listOf(DiffLine(Op.ADD, "x"), DiffLine(Op.ADD, "y")), actual)
    }

    @Test
    fun `when the after side is empty - then every line reads as removed`() {
        // given
        val before = listOf("x", "y")

        // when
        val actual = diffLines(before, emptyList())

        // then
        assertEquals(listOf(DiffLine(Op.DEL, "x"), DiffLine(Op.DEL, "y")), actual)
    }
    //endregion
}

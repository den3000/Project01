package ru.den.writes.code.agenticHub.mcps.projectfs

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * `replace_in_project_file` — the tool that edits a document without restating it, and
 * the one place where a hallucinated quote is supposed to become a visible error rather
 * than a silent wrong edit.
 */
class ProjectReplaceTest {

    //region a replacement that lands
    @Test
    fun `when the fragment occurs once - then it is replaced and the diff shows it`() {
        // given
        val project = writableProject(files = mapOf("a.md" to "one\ntwo\nthree\n"))

        // when
        val actual = project.writer.replace("a.md", old = "two", new = "TWO")

        // then
        assertContains(actual, "Обновлён a.md (+1 −1), заменено 1 место")
        assertContains(actual, "-two")
        assertContains(actual, "+TWO")
    }

    @Test
    fun `when a fragment is replaced - then the new content lands in the tree`() {
        // given
        val project = writableProject(files = mapOf("a.md" to "one\ntwo\n"))

        // when
        project.writer.replace("a.md", old = "two", new = "TWO")

        // then
        assertEquals("one\nTWO\n", project.io.contentOf("a.md"))
    }

    @Test
    fun `when replaceAll is set - then every occurrence goes`() {
        // given
        val project = writableProject(files = mapOf("a.md" to "x\ny\nx\n"))

        // when
        val actual = project.writer.replace("a.md", old = "x", new = "z", replaceAll = true)

        // then
        assertContains(actual, "заменено 2 мест(а)")
        assertEquals("z\ny\nz\n", project.io.contentOf("a.md"))
    }

    @Test
    fun `when the fragment carries a line-number gutter - then it is stripped before matching`() {
        // given
        val project = writableProject(files = mapOf("a.md" to "one\ntwo\nthree\n"))

        // when
        val actual = project.writer.replace("a.md", old = "2| two", new = "TWO")

        // then
        assertEquals("one\nTWO\nthree\n", project.io.contentOf("a.md"), "получено: $actual")
    }

    @Test
    fun `when the replacement is empty - then the fragment is deleted`() {
        // given
        val project = writableProject(files = mapOf("a.md" to "keep\ndrop\n"))

        // when
        project.writer.replace("a.md", old = "drop\n", new = "")

        // then
        assertEquals("keep\n", project.io.contentOf("a.md"))
    }
    //endregion

    //region a replacement that is refused
    @Test
    fun `when the fragment differs only in indentation - then the error points at that line`() {
        // given
        val project = writableProject(files = mapOf("a.kt" to "intro\n    val monitor = Monitor()\n"))

        // when
        val actual = project.writer.replace("a.kt", old = "        val monitor = Monitor()", new = "x")

        // then
        assertContains(actual, "Строка 2 совпадает по тексту, но отличается пробелами")
        assertEquals("intro\n    val monitor = Monitor()\n", project.io.contentOf("a.kt"))
    }

    @Test
    fun `when the fragment is ambiguous - then it is refused rather than guessed`() {
        // given
        val project = writableProject(files = mapOf("a.md" to "x\ny\nx\n"))

        // when
        val actual = project.writer.replace("a.md", old = "x", new = "z")

        // then
        assertContains(actual, "'old' встречается 2 раз(а) в 'a.md'")
        assertEquals("x\ny\nx\n", project.io.contentOf("a.md"), "файл не должен измениться")
    }

    @Test
    fun `when the fragment is empty - then it is refused`() {
        // given
        val project = writableProject(files = mapOf("a.md" to "text\n"))

        // when
        val actual = project.writer.replace("a.md", old = "", new = "x")

        // then
        assertEquals("projectfs error: 'old' не может быть пустым", actual)
    }

    @Test
    fun `when old and new are the same - then nothing is written`() {
        // given
        val project = writableProject(files = mapOf("a.md" to "same\n"))

        // when
        val actual = project.writer.replace("a.md", old = "same", new = "same")

        // then
        assertEquals("'a.md': без изменений (old и new совпадают)", actual)
    }

    @Test
    fun `when the file is absent - then it is reported as unreadable`() {
        // given
        val project = writableProject()

        // when
        val actual = project.writer.replace("missing.md", old = "x", new = "y")

        // then
        assertEquals("projectfs error: 'missing.md' не найден или нечитаем", actual)
    }

    @Test
    fun `when write extensions exclude the target - then the gate refuses the edit`() {
        // given
        val project = writableProject(files = mapOf("a.kt" to "код"), writeExtensions = setOf("md"))

        // when
        val actual = project.writer.replace("a.kt", old = "код", new = "другой")

        // then
        assertEquals("projectfs error: 'a.kt': запись разрешена только для .md", actual)
    }
    //endregion
}

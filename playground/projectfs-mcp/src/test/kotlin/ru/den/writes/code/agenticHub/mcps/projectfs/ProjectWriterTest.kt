package ru.den.writes.code.agenticHub.mcps.projectfs

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ProjectWriterTest {

    @Test
    fun `when the file does not exist - then it is created and the diff starts from nothing`() {
        // given
        val project = writableProject()

        // when
        val actual = project.writer.write("docs/report.md", "строка\n")

        // then
        assertContains(actual, "Создан docs/report.md (+1 −0)")
        assertContains(actual, "--- /dev/null")
    }

    @Test
    fun `when a file is written - then the content lands in the tree`() {
        // given
        val project = writableProject()

        // when
        project.writer.write("docs/report.md", "строка\n")

        // then
        assertEquals("строка\n", project.io.contentOf("docs/report.md"))
    }

    @Test
    fun `when an existing file is overwritten - then the change is counted both ways`() {
        // given
        val project = writableProject(files = mapOf("a.md" to "one\ntwo\n"))

        // when
        val actual = project.writer.write("a.md", "one\nTWO\n")

        // then
        assertContains(actual, "Обновлён a.md (+1 −1)")
    }

    @Test
    fun `when the content is identical - then nothing is written`() {
        // given
        val project = writableProject(files = mapOf("a.md" to "same\n"))

        // when
        val actual = project.writer.write("a.md", "same\n")

        // then
        assertEquals("'a.md': без изменений (содержимое совпадает)", actual)
    }

    @Test
    fun `when write extensions exclude the target - then the gate refuses it`() {
        // given
        val project = writableProject(writeExtensions = setOf("md"))

        // when
        val actual = project.writer.write("server/Main.kt", "код")

        // then
        assertEquals("projectfs error: 'server/Main.kt': запись разрешена только для .md", actual)
    }

    @Test
    fun `when the target sits under a generated directory - then the gate refuses it`() {
        // given
        val project = writableProject()

        // when
        val actual = project.writer.write("build/report.md", "текст")

        // then
        assertEquals("projectfs error: 'build/report.md': запись под 'build/' запрещена", actual)
    }
}

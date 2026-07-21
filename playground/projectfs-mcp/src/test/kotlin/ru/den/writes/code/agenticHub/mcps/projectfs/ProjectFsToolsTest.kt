package ru.den.writes.code.agenticHub.mcps.projectfs

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * That each tool's declared arguments actually reach its body. The bodies themselves are
 * covered by their own tests; what is easy to get wrong here is a name typo between the
 * schema a model reads and the key the dispatch looks up.
 */
class ProjectFsToolsTest {

    //region list_project_files
    @Test
    fun `when list is called with no arguments - then the whole project is listed`() {
        // given
        val tools = projectFsTools(files = mapOf("docs/a.md" to "a", "server/b.kt" to "b"))

        // when
        val actual = tools.listProjectFiles(args())

        // then
        assertEquals("docs/a.md (1 line)\nserver/b.kt (1 line)", actual)
    }

    @Test
    fun `when list is called with subdir ext and limit - then all three reach the listing`() {
        // given
        val tools = projectFsTools(
            files = mapOf("docs/a.md" to "a", "docs/b.md" to "b", "docs/c.kt" to "c", "server/d.md" to "d"),
        )

        // when
        val actual = tools.listProjectFiles(args("subdir" to "docs", "ext" to "md", "limit" to 1))

        // then
        assertEquals("docs/a.md (1 line)\n… показано 1 из 2; сузь subdir или ext", actual)
    }
    //endregion

    //region read_project_file
    @Test
    fun `when read is called with path offset and limit - then all three reach the reader`() {
        // given
        val tools = projectFsTools(files = mapOf("a.md" to "one\ntwo\nthree\nfour\n"))

        // when
        val actual = tools.readProjectFile(args("path" to "a.md", "offset" to 2, "limit" to 2))

        // then
        val expected = "a.md (lines 2-3 of 4)\n2| two\n3| three\n" +
            "… файл длиннее: продолжить read_project_file(path=\"a.md\", offset=4, limit=2)"
        assertEquals(expected, actual)
    }

    @Test
    fun `when read is called without a path - then the missing argument is named`() {
        // given
        val tools = projectFsTools(files = mapOf("a.md" to "one\n"))

        // when
        val actual = tools.readProjectFile(args())

        // then
        assertEquals("projectfs error: обязателен аргумент 'path'", actual)
    }
    //endregion

    //region search_project_files
    @Test
    fun `when search is called with its filters - then each one reaches the search`() {
        // given
        val tools = projectFsTools(
            files = mapOf("docs/a.md" to "Needle\n", "docs/b.kt" to "needle\n", "server/c.md" to "needle\n"),
        )

        // when
        val actual = tools.searchProjectFiles(
            args("query" to "needle", "subdir" to "docs", "ext" to "md", "ignoreCase" to true),
        )

        // then
        assertEquals("docs/a.md:1: Needle", actual)
    }

    @Test
    fun `when search is called with filesOnly - then the map is returned instead of the lines`() {
        // given
        val tools = projectFsTools(files = mapOf("a.md" to "x\nx\n"))

        // when
        val actual = tools.searchProjectFiles(args("query" to "x", "filesOnly" to true))

        // then
        assertEquals("a.md (2)\nитого 2 совпадени(й) в 1 файл(ах)", actual)
    }

    @Test
    fun `when search is called without a query - then the missing argument is named`() {
        // given
        val tools = projectFsTools(files = mapOf("a.md" to "x\n"))

        // when
        val actual = tools.searchProjectFiles(args())

        // then
        assertEquals("projectfs error: обязателен аргумент 'query'", actual)
    }
    //endregion

    //region the write tools
    @Test
    fun `when write is called with path and content - then both reach the writer`() {
        // given
        val tools = projectFsTools()

        // when
        val actual = tools.writeProjectFile(args("path" to "docs/report.md", "content" to "текст\n"))

        // then
        assertContains(actual, "Создан docs/report.md")
    }

    @Test
    fun `when write is called without content - then the missing argument is named`() {
        // given
        val tools = projectFsTools()

        // when
        val actual = tools.writeProjectFile(args("path" to "docs/report.md"))

        // then
        assertEquals("projectfs error: обязателен аргумент 'content'", actual)
    }

    @Test
    fun `when replace is called with its arguments - then each one reaches the writer`() {
        // given
        val tools = projectFsTools(files = mapOf("a.md" to "x\ny\nx\n"))

        // when
        val actual = tools.replaceInProjectFile(
            args("path" to "a.md", "old" to "x", "new" to "z", "replaceAll" to true),
        )

        // then
        assertContains(actual, "заменено 2 мест(а)")
    }

    @Test
    fun `when replace is called with an empty new - then the deletion is accepted`() {
        // given
        val tools = projectFsTools(files = mapOf("a.md" to "keep\ndrop\n"))

        // when
        val actual = tools.replaceInProjectFile(args("path" to "a.md", "old" to "drop\n", "new" to ""))

        // then
        assertContains(actual, "Обновлён a.md")
    }

    @Test
    fun `when replace is called without old - then the missing argument is named`() {
        // given
        val tools = projectFsTools(files = mapOf("a.md" to "text\n"))

        // when
        val actual = tools.replaceInProjectFile(args("path" to "a.md", "new" to "z"))

        // then
        assertEquals("projectfs error: обязателен аргумент 'old'", actual)
    }
    //endregion
}

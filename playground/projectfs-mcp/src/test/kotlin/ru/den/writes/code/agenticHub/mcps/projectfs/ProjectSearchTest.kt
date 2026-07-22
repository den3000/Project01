package ru.den.writes.code.agenticHub.mcps.projectfs

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ProjectSearchTest {

    //region matching
    @Test
    fun `when a term occurs - then each hit comes back as path colon line colon text`() {
        // given
        val search = projectSearch(files = mapOf("a.md" to "intro\nuses NetworkMonitor here\n"))

        // when
        val actual = search.search("NetworkMonitor")

        // then
        assertEquals("a.md:2: uses NetworkMonitor here", actual)
    }

    @Test
    fun `when the query holds regex metacharacters - then they are matched literally`() {
        // given
        val search = projectSearch(files = mapOf("a.md" to "version a.b.c\nversion axbxc\n"))

        // when
        val actual = search.search("a.b.c")

        // then
        assertEquals("a.md:1: version a.b.c", actual)
    }

    @Test
    fun `when regex is requested - then the query is compiled as a pattern`() {
        // given
        val search = projectSearch(files = mapOf("a.md" to "value 42\nvalue abc\n"))

        // when
        val actual = search.search("value [0-9]+", regex = true)

        // then
        assertEquals("a.md:1: value 42", actual)
    }

    @Test
    fun `when the regex does not compile - then the error quotes it`() {
        // given
        val search = projectSearch(files = mapOf("a.md" to "text"))

        // when
        val actual = search.search("[unclosed", regex = true)

        // then
        assertContains(actual, "projectfs error: некорректный regex '[unclosed'")
    }

    @Test
    fun `when case is ignored - then differently cased hits are found`() {
        // given
        val search = projectSearch(files = mapOf("a.md" to "Compose Multiplatform\n"))

        // when
        val actual = search.search("compose", ignoreCase = true)

        // then
        assertEquals("a.md:1: Compose Multiplatform", actual)
    }

    @Test
    fun `when a matching line has surrounding whitespace - then it is trimmed in the result`() {
        // given
        val search = projectSearch(files = mapOf("a.kt" to "    val monitor = Monitor()    \n"))

        // when
        val actual = search.search("Monitor")

        // then
        assertEquals("a.kt:1: val monitor = Monitor()", actual)
    }
    //endregion

    //region shaping the result
    @Test
    fun `when filesOnly is set - then only paths and their hit counts come back`() {
        // given
        val search = projectSearch(files = mapOf("a.md" to "x\nx\n", "b.md" to "x\n"))

        // when
        val actual = search.search("x", filesOnly = true)

        // then
        assertEquals("a.md (2)\nb.md (1)\nитого 3 совпадени(й) в 2 файл(ах)", actual)
    }

    @Test
    fun `when filesOnly spans more files than it lists - then the rest are counted not dropped`() {
        // given
        val files = (1..FILES_ONLY_LIMIT + 7).associate { "f$it.md" to "x\n" }
        val search = projectSearch(files = files)

        // when
        val actual = search.search("x", filesOnly = true)

        // then
        assertEquals(FILES_ONLY_LIMIT, actual.lines().count { it.endsWith(" (1)") })
        assertContains(actual, "… ещё 7 файл(ов)")
        assertContains(actual, "итого ${FILES_ONLY_LIMIT + 7} совпадени(й) в ${FILES_ONLY_LIMIT + 7} файл(ах)")
    }

    @Test
    fun `when one file is dense with hits - then its share of the result is capped`() {
        // given
        val search = projectSearch(files = mapOf("a.md" to "x\n".repeat(MATCHES_PER_FILE + 5)))

        // when
        val actual = search.search("x")

        // then
        assertEquals(MATCHES_PER_FILE, actual.lines().count { it.startsWith("a.md:") })
    }

    @Test
    fun `when maxMatches is above the ceiling - then it is clamped to the ceiling`() {
        // given
        val files = (1..SEARCH_MATCHES_MAX + 20).associate { "f$it.md" to "x\n" }
        val search = projectSearch(files = files)

        // when
        val actual = search.search("x", maxMatches = SEARCH_MATCHES_MAX * 10)

        // then
        assertEquals(SEARCH_MATCHES_MAX, actual.lines().count { it.contains(":1: x") })
    }

    @Test
    fun `when maxMatches is below one - then a single match is returned`() {
        // given
        val search = projectSearch(files = mapOf("a.md" to "x\n", "b.md" to "x\n"))

        // when
        val actual = search.search("x", maxMatches = 0)

        // then
        assertContains(actual, "… показано 1 из 2")
    }

    @Test
    fun `when the result is cut short - then it says how to narrow the search`() {
        // given
        val search = projectSearch(files = mapOf("a.md" to "x\n", "b.md" to "x\n", "c.md" to "x\n"))

        // when
        val actual = search.search("x", maxMatches = 2)

        // then
        assertContains(actual, "сузь запрос, subdir или используй filesOnly=true")
    }
    //endregion

    //region what is skipped
    @Test
    fun `when a file is past the large threshold - then it is not searched`() {
        // given
        val search = projectSearch(
            files = mapOf("dump.sql" to "needle here", "a.md" to "needle here"),
            sizes = mapOf("dump.sql" to 2 * LARGE_FILE_BYTES),
        )

        // when
        val actual = search.search("needle")

        // then
        assertEquals("a.md:1: needle here", actual)
    }

    @Test
    fun `when a file sniffs as binary - then it is not searched`() {
        // given
        val search = projectSearch(
            files = mapOf("logo.dat" to "needle" + Char(0) + "bytes", "a.md" to "needle here"),
        )

        // when
        val actual = search.search("needle")

        // then
        assertEquals("a.md:1: needle here", actual)
    }

    @Test
    fun `when subdir and ext narrow the search - then only those files are scanned`() {
        // given
        val search = projectSearch(
            files = mapOf("docs/a.md" to "x", "docs/b.kt" to "x", "server/c.md" to "x"),
        )

        // when
        val actual = search.search("x", subdir = "docs", ext = "md")

        // then
        assertEquals("docs/a.md:1: x", actual)
    }
    //endregion
}

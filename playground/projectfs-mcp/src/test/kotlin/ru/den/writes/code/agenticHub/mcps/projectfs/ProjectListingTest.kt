package ru.den.writes.code.agenticHub.mcps.projectfs

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectListingTest {

    //region what a listing shows
    @Test
    fun `when a project is listed - then every path carries its line count`() {
        // given
        val listing = projectListing(files = mapOf("README.md" to "a\nb\nc\n"))

        // when
        val actual = listing.list()

        // then
        assertEquals("README.md (3 lines)", actual)
    }

    @Test
    fun `when a file holds a single line - then the count is singular`() {
        // given
        val listing = projectListing(files = mapOf("VERSION" to "1.2.3"))

        // when
        val actual = listing.list()

        // then
        assertEquals("VERSION (1 line)", actual)
    }

    @Test
    fun `when a file is past the large threshold - then its size is shown instead of a count`() {
        // given
        val listing = projectListing(
            files = mapOf("dump.sql" to "irrelevant"),
            sizes = mapOf("dump.sql" to 2 * LARGE_FILE_BYTES),
        )

        // when
        val actual = listing.list()

        // then
        assertEquals("dump.sql (large, ${2 * LARGE_FILE_BYTES / 1024} KB)", actual)
    }

    @Test
    fun `when the listing is sorted - then paths come back in order`() {
        // given
        val listing = projectListing(files = mapOf("b.md" to "b", "a.md" to "a", "c.md" to "c"))

        // when
        val actual = listing.list()

        // then
        assertEquals("a.md (1 line)\nb.md (1 line)\nc.md (1 line)", actual)
    }
    //endregion

    //region what a listing hides
    @Test
    fun `when the tree holds binaries - then they are not listed`() {
        // given
        val listing = projectListing(
            files = mapOf("logo.png" to "bytes", "shared.klib" to "bytes", "README.md" to "text"),
        )

        // when
        val actual = listing.list()

        // then
        assertEquals("README.md (1 line)", actual)
    }

    @Test
    fun `when the tree holds credential files - then they are absent rather than marked`() {
        // given
        val listing = projectListing(files = mapOf("local.properties" to "key=secret", "README.md" to "text"))

        // when
        val actual = listing.list()

        // then
        assertEquals("README.md (1 line)", actual)
        assertFalse(actual.contains("local.properties"), "имя секретного файла не должно попадать в листинг")
    }

    @Test
    fun `when the tree holds desktop-OS droppings - then they are not listed`() {
        // given
        val listing = projectListing(
            files = mapOf(
                ".DS_Store" to "bytes",
                "docs/.DS_Store" to "bytes",
                "Thumbs.db" to "bytes",
                "README.md" to "text",
            ),
        )

        // when
        val actual = listing.list()

        // then
        assertEquals("README.md (1 line)", actual)
    }

    @Test
    fun `when the tree holds generated directories - then the walk never surfaces them`() {
        NOISE_SEGMENTS.forEach { segment ->
            // given
            val listing = projectListing(files = mapOf("$segment/out.txt" to "x", "README.md" to "text"))

            // when
            val actual = listing.list()

            // then
            assertEquals("README.md (1 line)", actual, "каталог '$segment' не должен попадать в листинг")
        }
    }

    @Test
    fun `when a generated directory is nested - then it is skipped at any depth`() {
        // given
        val listing = projectListing(
            files = mapOf(
                ".kotlin/metadata/transformed/androidx.collection-1.5.0-commonMain.klib" to "x",
                "shared-ui/build/generated/appconfig/AppConfig.kt" to "x",
                "README.md" to "text",
            ),
        )

        // when
        val actual = listing.list()

        // then
        assertEquals("README.md (1 line)", actual)
    }
    //endregion

    //region narrowing and limits
    @Test
    fun `when a subdir is given - then only paths under it are listed`() {
        // given
        val listing = projectListing(files = mapOf("docs/a.md" to "a", "server/b.md" to "b"))

        // when
        val actual = listing.list(subdir = "docs")

        // then
        assertEquals("docs/a.md (1 line)", actual)
    }

    @Test
    fun `when an extension filter is given - then it accepts a dotted comma-separated list`() {
        // given
        val listing = projectListing(files = mapOf("a.md" to "a", "b.kt" to "b", "c.txt" to "c"))

        // when
        val actual = listing.list(ext = ".md, kt")

        // then
        assertEquals("a.md (1 line)\nb.kt (1 line)", actual)
    }

    @Test
    fun `when a limit above the ceiling is given - then it is clamped to the ceiling`() {
        // given
        val files = (1..LIST_LIMIT_DEFAULT + 10).associate { "f$it.md" to "x" }
        val listing = projectListing(files = files)

        // when
        val actual = listing.list(limit = LIST_LIMIT_DEFAULT * 10)

        // then
        assertEquals(LIST_LIMIT_DEFAULT, actual.lines().count { it.endsWith("(1 line)") })
    }

    @Test
    fun `when a limit below one is given - then at least one path is listed`() {
        // given
        val listing = projectListing(files = mapOf("a.md" to "a", "b.md" to "b"))

        // when
        val actual = listing.list(limit = 0)

        // then
        assertEquals("a.md (1 line)\n… показано 1 из 2; сузь subdir или ext", actual)
    }

    @Test
    fun `when the listing is cut short - then it says how to narrow the query`() {
        // given
        val listing = projectListing(files = mapOf("a.md" to "a", "b.md" to "b", "c.md" to "c"))

        // when
        val actual = listing.list(limit = 2)

        // then
        assertContains(actual, "… показано 2 из 3; сузь subdir или ext")
    }
    //endregion

    //region empty and unreadable
    @Test
    fun `when nothing matches the filters - then the notice names them`() {
        // given
        val listing = projectListing(files = mapOf("server/a.md" to "a"))

        // when
        val actual = listing.list(subdir = "server", ext = "kt")

        // then
        assertContains(actual, "фильтр ext='kt' не выбрал ни одного файла под 'server'")
        assertContains(actual, "НЕ означает, что термина нет")
    }

    @Test
    fun `when a file vanishes between walk and stat - then it is reported as unread`() {
        // given
        val io = FileIoFake(files = mapOf("ghost.md" to "text"))
        val listing = ProjectListing(paths = projectPaths(), io = VanishingFileIo(io))

        // when
        val actual = listing.list()

        // then
        assertTrue(actual.startsWith("ghost.md (не прочитан)"), "получено: $actual")
    }
    //endregion
}

/** A tree whose paths are walkable but whose files are gone by the time they are stat'ed. */
private class VanishingFileIo(private val delegate: FileIo) : FileIo by delegate {
    override fun stat(absolute: String): FileStat? = null
}

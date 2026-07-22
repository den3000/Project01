package ru.den.writes.code.agenticHub.mcps.projectfs

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `ext` filter, and what it says when it selects nothing.
 *
 * This is the failure that cost the most: `ext="gradle.kts"` matched no file, the search
 * answered "no matches", and the model — correctly, given what it was told — wrote up the
 * term as absent from the project. A whole invariants report of false breaches came out of
 * it, every claim "grounded" in a search that had looked at zero files.
 */
class ProjectExtensionFilterTest {

    //region matching
    @Test
    fun `when the filter is a compound extension - then a file carrying it matches`() {
        // given
        val extensions = parseExtensions("gradle.kts")

        // when - then
        assertTrue(matchesExtension("shared-ui/build.gradle.kts", extensions))
        assertTrue(matchesExtension("build.aurora.gradle.kts", extensions))
    }

    @Test
    fun `when the filter is the trailing segment - then the same file still matches`() {
        // given
        val extensions = parseExtensions("kts")

        // when - then
        assertTrue(matchesExtension("shared-ui/build.gradle.kts", extensions))
    }

    @Test
    fun `when the filter names another extension - then the file does not match`() {
        // given
        val extensions = parseExtensions("kt")

        // when - then
        assertFalse(matchesExtension("build.gradle.kts", extensions), "kt не должен цеплять kts")
        assertFalse(matchesExtension("README.md", extensions))
    }

    @Test
    fun `when no filter is given - then everything matches`() {
        // given
        val extensions = parseExtensions(null)

        // when - then
        assertTrue(matchesExtension("anything.kt", extensions))
        assertTrue(matchesExtension("Makefile", extensions))
    }
    //endregion

    //region what an empty selection says
    @Test
    fun `when a compound extension is searched - then the term is actually found`() {
        // given
        val search = projectSearch(files = mapOf("shared-ui/build.gradle.kts" to "alias(libs.plugins.ksp)\n"))

        // when
        val actual = search.search("ksp", ext = "gradle.kts")

        // then
        assertEquals("shared-ui/build.gradle.kts:1: alias(libs.plugins.ksp)", actual)
    }

    @Test
    fun `when the filter selects no file - then the search says so instead of reporting no matches`() {
        // given
        val search = projectSearch(files = mapOf("README.md" to "ksp\n", "notes.txt" to "ksp\n"))

        // when
        val actual = search.search("ksp", ext = "kt")

        // then
        assertContains(actual, "фильтр ext='kt' не выбрал ни одного файла")
        assertContains(actual, "НЕ означает, что термина нет")
        assertContains(actual, ".md (1)")
    }

    @Test
    fun `when the subdir holds nothing at all - then that is said rather than blamed on the filter`() {
        // given
        val search = projectSearch(files = mapOf("docs/a.md" to "text"))

        // when
        val actual = search.search("anything", subdir = "server", ext = "kt")

        // then
        assertEquals("projectfs error: под 'server' нет файлов вовсе — проверь путь подкаталога.", actual)
    }

    @Test
    fun `when files are selected but the term is absent - then the plain no-match notice stands`() {
        // given
        val search = projectSearch(files = mapOf("a.kt" to "совсем другое\n"))

        // when
        val actual = search.search("ksp", ext = "kt")

        // then
        assertEquals("(совпадений нет: 'ksp')", actual)
    }
    //endregion
}

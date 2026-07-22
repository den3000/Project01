package ru.den.writes.code.agenticHub.mcps.projectfs

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * What a search says when it finds nothing.
 *
 * Split out from [ProjectSearchTest] because this is the behaviour the server exists to
 * get right: an empty result that only reports emptiness reads to a model as "there is
 * nothing to find", and it stops looking.
 */
class ProjectSearchEmptyResultTest {

    @Test
    fun `when a single term is absent - then the notice states it plainly`() {
        // given
        val search = projectSearch(files = mapOf("docs/a.md" to "text"))

        // when
        val actual = search.search("Missing", subdir = "docs")

        // then
        assertEquals("(совпадений нет: 'Missing' под 'docs')", actual)
    }

    @Test
    fun `when a phrase is absent but its words are not - then the hint names where they occur`() {
        // given
        val search = projectSearch(
            files = mapOf("a.md" to "Compose здесь\n", "b.md" to "версия 0.0.4-aurora\n"),
        )

        // when
        val actual = search.search("Compose 0.0.4-aurora")

        // then
        assertContains(actual, "'Compose' — в 1 файл(ах)")
        assertContains(actual, "'0.0.4-aurora' — в 1 файл(ах)")
        assertContains(actual, "Ищи по одному термину, а не фразой.")
    }

    @Test
    fun `when neither the phrase nor its words occur - then the hint says to change the term`() {
        // given
        val search = projectSearch(files = mapOf("a.md" to "нечто иное\n"))

        // when
        val actual = search.search("Compose Multiplatform")

        // then
        assertContains(actual, "Отдельные слова запроса тоже не встречаются")
    }

    @Test
    fun `when a blank query is given - then it is refused`() {
        // given
        val search = projectSearch(files = mapOf("a.md" to "text"))

        // when
        val actual = search.search("   ")

        // then
        assertEquals("projectfs error: обязателен непустой 'query'", actual)
    }

    @Test
    fun `when a regex finds nothing - then no word-level hint is offered`() {
        // given
        val search = projectSearch(files = mapOf("a.md" to "Compose здесь\n"))

        // when
        val actual = search.search("Compose [0-9]+", regex = true)

        // then
        assertEquals("(совпадений нет: 'Compose [0-9]+')", actual)
        assertFalse(actual.contains("по отдельности"), "для regex разбор на слова бессмыслен")
    }
}

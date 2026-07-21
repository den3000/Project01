package ru.den.writes.code.agenticHub.mcps.projectfs

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The wording a tool falls back on when it found nothing. Pure functions of text, and the
 * part that actually steers a model away from a dead end — so it is pinned down here
 * rather than left to whatever a tool body happens to say.
 */
class ProjectHintsTest {

    //region probeWords
    @Test
    fun `when the query is a single term - then there is nothing to decompose`() {
        // given
        val query = "NetworkMonitor"

        // when
        val actual = probeWords(query)

        // then
        assertEquals(emptyList(), actual)
    }

    @Test
    fun `when the query holds short words - then only the substantial ones are probed`() {
        // given
        val query = "на Compose из AGENTS"

        // when
        val actual = probeWords(query)

        // then
        assertEquals(listOf("Compose", "AGENTS"), actual)
    }

    @Test
    fun `when the query holds more words than the probe budget - then it is capped`() {
        // given
        val query = (1..MAX_PROBE_WORDS + 3).joinToString(" ") { "word$it" }

        // when
        val actual = probeWords(query)

        // then
        assertEquals(MAX_PROBE_WORDS, actual.size)
    }
    //endregion

    //region noMatchHint
    @Test
    fun `when there are no probes - then the notice just states the miss`() {
        // given
        val query = "NetworkMonitor"

        // when
        val actual = noMatchHint(query, subdir = null, probes = emptyList(), hits = emptyList())

        // then
        assertEquals("(совпадений нет: 'NetworkMonitor')", actual)
    }

    @Test
    fun `when a subdir narrowed the search - then the notice names it`() {
        // given
        val query = "NetworkMonitor"

        // when
        val actual = noMatchHint(query, subdir = "server", probes = emptyList(), hits = emptyList())

        // then
        assertEquals("(совпадений нет: 'NetworkMonitor' под 'server')", actual)
    }

    @Test
    fun `when no probe word occurs either - then the hint says to take another term`() {
        // given
        val probes = listOf("Compose", "Multiplatform")

        // when
        val actual = noMatchHint("Compose Multiplatform", subdir = null, probes = probes, hits = listOf(0, 0))

        // then
        assertContains(actual, "Отдельные слова запроса тоже не встречаются — возьми другой термин.")
    }

    @Test
    fun `when some probe words occur - then the hint names them with their file counts`() {
        // given
        val probes = listOf("Compose", "0.0.4-aurora")

        // when
        val actual = noMatchHint("Compose 0.0.4-aurora", subdir = null, probes = probes, hits = listOf(12, 3))

        // then
        assertContains(actual, "запрос ищется как одна точная подстрока целиком")
        assertContains(actual, "'Compose' — в 12 файл(ах), '0.0.4-aurora' — в 3 файл(ах)")
        assertContains(actual, "Ищи по одному термину, а не фразой.")
    }
    //endregion

    //region notFoundHint
    @Test
    fun `when a line matches but for its indentation - then the hint points at that line`() {
        // given
        val text = "intro\n    val monitor = Monitor()\noutro\n"

        // when
        val actual = notFoundHint("a.kt", text, "        val monitor = Monitor()")

        // then
        assertContains(actual, "Строка 2 совпадает по тексту, но отличается пробелами")
        assertContains(actual, "read_project_file(path=\"a.kt\", offset=1, limit=10)")
    }

    @Test
    fun `when a line shares the needle's opening - then the hint points at that line`() {
        // given
        val text = (1..9).joinToString("\n") { "filler$it" } + "\nПроверка инвариантов проекта живёт тут\n"

        // when
        val actual = notFoundHint("a.md", text, "Проверка инвариантов проекта отсутствует")

        // then
        assertContains(actual, "Похожая строка 10:")
        assertContains(actual, "read_project_file(path=\"a.md\", offset=8, limit=10)")
    }

    @Test
    fun `when nothing in the file resembles the needle - then the hint asks for a verbatim reread`() {
        // given
        val text = "совершенно другой текст\n"

        // when
        val actual = notFoundHint("a.md", text, "ничего похожего здесь нет")

        // then
        assertEquals(
            "projectfs error: 'old' не найден в 'a.md'. Перечитай нужный участок " +
                "read_project_file(path=\"a.md\") и передай текст дословно, без номеров строк.",
            actual,
        )
    }
    //endregion
}

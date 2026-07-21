package ru.den.writes.code.agenticHub.mcps.projectfs

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two guarantees the dispatch layer exists to provide — every tool answers with text
 * whatever happens, and nothing it answers with is unbounded — plus the argument reading
 * every tool shares. Neither is specific to a tool, and both are what the rest of the
 * server assumes.
 */
class ProjectFsContractTest {

    //region totality
    @Test
    fun `when a tool body throws - then the failure comes back as tool text`() {
        // given
        val tools = projectFsToolsOver(ExplodingFileIo("диск отвалился"))

        // when
        val actual = tools.listProjectFiles(args())

        // then
        assertEquals("projectfs error: диск отвалился", actual)
    }

    @Test
    fun `when a tool body throws without a message - then the failure names the exception type`() {
        // given
        val tools = projectFsToolsOver(ExplodingFileIo(null))

        // when
        val actual = tools.listProjectFiles(args())

        // then
        assertEquals("projectfs error: IllegalStateException", actual)
    }
    //endregion

    //region bounding
    @Test
    fun `when the output fits the cap - then it is returned untouched`() {
        // given
        val text = "a".repeat(MAX_OUTPUT_CHARS)

        // when
        val actual = text.clampTo(MAX_OUTPUT_CHARS)

        // then
        assertEquals(text, actual)
    }

    @Test
    fun `when the output exceeds the cap - then it is cut and says how much was dropped`() {
        // given
        val text = "a".repeat(MAX_OUTPUT_CHARS + 10)

        // when
        val actual = text.clampTo(MAX_OUTPUT_CHARS)

        // then
        assertTrue(actual.startsWith("a".repeat(MAX_OUTPUT_CHARS)), "начало должно сохраниться")
        assertContains(actual, "вывод обрезан: $MAX_OUTPUT_CHARS из ${MAX_OUTPUT_CHARS + 10} символов")
    }

    @Test
    fun `when the cut falls inside a surrogate pair - then the pair is dropped whole`() {
        // given
        val emoji = "🚀"
        val text = "a".repeat(MAX_OUTPUT_CHARS - 1) + emoji

        // when
        val actual = text.clampTo(MAX_OUTPUT_CHARS)

        // then
        assertEquals("a".repeat(MAX_OUTPUT_CHARS - 1), actual.substringBefore("\n…"))
        assertContains(actual, "вывод обрезан: ${MAX_OUTPUT_CHARS - 1} из ${MAX_OUTPUT_CHARS + 1} символов")
    }

    @Test
    fun `when a tool returns more than the cap - then the dispatch clamps it`() {
        // given
        val files = (1..LIST_LIMIT_DEFAULT).associate { "very-long-file-name-number-$it.md" to "x" }
        val tools = projectFsTools(files = files)

        // when
        val actual = tools.listProjectFiles(args())

        // then
        assertTrue(actual.length <= MAX_OUTPUT_CHARS + CLAMP_NOTICE_SLACK, "длина ${actual.length}")
        assertContains(actual, "вывод обрезан")
    }
    //endregion

    //region argument reading
    @Test
    fun `when an argument is absent - then the optional readers answer null`() {
        // given
        val arguments = args()

        // when - then
        assertNull(arguments.string("subdir"))
        assertNull(arguments.int("limit"))
    }

    @Test
    fun `when an integer argument arrives as a JSON number - then it is read`() {
        // given
        val arguments = args("limit" to 20)

        // when
        val actual = arguments.int("limit")

        // then
        assertEquals(20, actual)
    }

    @Test
    fun `when an integer argument arrives as a string - then it is still read`() {
        // given
        val arguments = args("limit" to "20")

        // when
        val actual = arguments.int("limit")

        // then
        assertEquals(20, actual)
    }

    @Test
    fun `when a boolean argument arrives in either form - then both are read`() {
        // given
        val asBoolean = args("regex" to true)
        val asString = args("regex" to "TRUE")

        // when - then
        assertTrue(asBoolean.bool("regex", default = false))
        assertTrue(asString.bool("regex", default = false))
        assertTrue(args().bool("regex", default = true), "отсутствующий аргумент берёт значение по умолчанию")
    }

    @Test
    fun `when a required argument is blank - then reading it reports which one is missing`() {
        // given
        val arguments = args("path" to "  ")

        // when
        val actual = runCatching { arguments.nonBlank("path") }.exceptionOrNull()

        // then
        assertEquals("обязателен аргумент 'path'", actual?.message)
    }

    @Test
    fun `when a required argument is legitimately empty - then presence alone is enough`() {
        // given
        val arguments = args("new" to "")

        // when
        val actual = arguments.present("new")

        // then
        assertEquals("", actual)
    }
    //endregion

    private companion object {
        /** The clamp notice is appended after the cut, so the result runs slightly past the cap. */
        const val CLAMP_NOTICE_SLACK = 64
    }
}

/** A tree whose every read fails — the way a tool body blows up in production. */
private class ExplodingFileIo(private val message: String?) : FileIo {
    private fun boom(): Nothing = throw IllegalStateException(message)

    override fun walk(): List<String> = boom()
    override fun stat(absolute: String): FileStat? = boom()
    override fun size(absolute: String): Long = boom()
    override fun read(absolute: String): String? = boom()
    override fun exists(absolute: String): Boolean = boom()
    override fun write(absolute: String, text: String) = boom()
}

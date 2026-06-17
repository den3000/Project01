package ru.den.writes.code.project01.cliJvm.cliArgs

import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.cliJvm.memory.MemoryMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class CliArgsChatMemoryFlagsTest {

    //region -memory-mode

    @Test
    fun `when -memory-mode preamble passed - then memoryMode is PREAMBLE`() {
        // given
        val args = arrayOf("-prompt", "hi", "-memory-mode", "preamble")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals(MemoryMode.PREAMBLE, chat.memoryMode)
    }

    @Test
    fun `when -memory-mode system passed - then memoryMode is SYSTEM`() {
        // given
        val args = arrayOf("-prompt", "hi", "-memory-mode", "system")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals(MemoryMode.SYSTEM, chat.memoryMode)
    }

    @Test
    fun `when -memory-mode has unknown value - then InvalidArgumentValue on -memory-mode`() {
        // given
        val args = arrayOf("-prompt", "hi", "-memory-mode", "shrug")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-memory-mode", ex.argName)
    }

    @Test
    fun `when -memory-mode used with -oneshot - then InvalidArgumentValue on -memory-mode`() {
        // given
        val args = arrayOf("-prompt", "hi", "-oneshot", "-memory-mode", "preamble")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-memory-mode", ex.argName)
    }

    @Test
    fun `when no -memory-mode passed - then memoryMode and task are null`() {
        // given
        val args = arrayOf("-prompt", "hi")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertNull(chat.memoryMode)
        assertNull(chat.task)
    }
    //endregion

    //region -task

    @Test
    fun `when -task used with -memory-mode - then task lands on Chat`() {
        // given
        val args = arrayOf("-prompt", "hi", "-memory-mode", "preamble", "-task", "auth")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals("auth", chat.task)
    }

    @Test
    fun `when -task used without -memory-mode - then InvalidArgumentValue on -task`() {
        // given
        val args = arrayOf("-prompt", "hi", "-task", "auth")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-task", ex.argName)
    }

    @Test
    fun `when -task used with -oneshot - then InvalidArgumentValue on -task`() {
        // given
        val args = arrayOf("-prompt", "hi", "-oneshot", "-task", "auth")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-task", ex.argName)
    }
    //endregion

    //region -profile

    @Test
    fun `when -profile used without -memory-mode - then InvalidArgumentValue on -profile`() {
        // given
        val args = arrayOf("-prompt", "hi", "-profile", "kotlin-senior")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-profile", ex.argName)
    }

    @Test
    fun `when -profile used with -memory-mode - then profile lands on Chat`() {
        // given
        val args = arrayOf("-prompt", "hi", "-memory-mode", "preamble", "-profile", "kotlin-senior")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals("kotlin-senior", chat.profile)
    }
    //endregion

    private fun parseCliArgsWithDummyKeys(vararg args: String): CliArgs =
        CliArgs.from(
            args = arrayOf(*args),
            geminiApiKey = DUMMY_GEMINI_KEY,
            openRouterApiKey = DUMMY_OPENROUTER_KEY,
            huggingFaceApiKey = DUMMY_HUGGINGFACE_KEY,
        )

    private companion object {
        const val DUMMY_GEMINI_KEY = "test-gemini-key"
        const val DUMMY_OPENROUTER_KEY = "test-openrouter-key"
        const val DUMMY_HUGGINGFACE_KEY = "test-huggingface-key"
    }
}

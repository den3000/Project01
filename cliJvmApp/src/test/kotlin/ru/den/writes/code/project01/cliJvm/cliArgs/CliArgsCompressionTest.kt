package ru.den.writes.code.project01.cliJvm.cliArgs

import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.cliJvm.ContextStrategyKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CliArgsCompressionTest {

    //region -compress happy path

    @Test
    fun `when -compress passed alone - then SUMMARY strategy with default knobs`() {
        // given
        val args = arrayOf("-prompt", "hi", "-compress")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals(ContextStrategyKind.SUMMARY, chat.strategy)
        assertEquals(6, chat.keepLast)
        assertEquals(10, chat.summarizeEvery)
    }

    @Test
    fun `when -compress with -keepLast and -summarizeEvery - then both carried through`() {
        // given
        val args = arrayOf("-prompt", "hi", "-compress", "-keepLast", "4", "-summarizeEvery", "20")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals(ContextStrategyKind.SUMMARY, chat.strategy)
        assertEquals(4, chat.keepLast)
        assertEquals(20, chat.summarizeEvery)
    }

    @Test
    fun `when -keepLast is zero under -compress - then accepted`() {
        // given
        val args = arrayOf("-prompt", "hi", "-compress", "-keepLast", "0")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals(0, chat.keepLast)
    }
    //endregion

    //region tuning flags require a compatible strategy

    @Test
    fun `when -keepLast used without -compress - then InvalidArgumentValue on -keepLast`() {
        // given
        val args = arrayOf("-prompt", "hi", "-keepLast", "4")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-keepLast", ex.argName)
    }

    @Test
    fun `when -summarizeEvery used without -compress - then InvalidArgumentValue on -summarizeEvery`() {
        // given
        val args = arrayOf("-prompt", "hi", "-summarizeEvery", "10")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-summarizeEvery", ex.argName)
    }
    //endregion

    //region tuning flag value bounds

    @Test
    fun `when -keepLast is negative - then InvalidArgumentValue on -keepLast`() {
        // given
        val args = arrayOf("-prompt", "hi", "-compress", "-keepLast", "-3")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-keepLast", ex.argName)
    }

    @Test
    fun `when -summarizeEvery is below 2 - then InvalidArgumentValue on -summarizeEvery`() {
        // given
        val args = arrayOf("-prompt", "hi", "-compress", "-summarizeEvery", "1")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-summarizeEvery", ex.argName)
    }
    //endregion

    //region incompatible modes

    @Test
    fun `when -compress combined with -oneshot - then InvalidArgumentValue on -compress`() {
        // given
        val args = arrayOf("-prompt", "hi", "-oneshot", "-compress")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-compress", ex.argName)
    }

    @Test
    fun `when -keepLast combined with -oneshot - then InvalidArgumentValue on -keepLast`() {
        // given
        val args = arrayOf("-prompt", "hi", "-oneshot", "-keepLast", "4")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-keepLast", ex.argName)
    }

    @Test
    fun `when -compress combined with -inflate - then InvalidArgumentValue on -compress`() {
        // given
        val args = arrayOf("-inflate", "5", "-session", "foo", "-compress")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-compress", ex.argName)
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

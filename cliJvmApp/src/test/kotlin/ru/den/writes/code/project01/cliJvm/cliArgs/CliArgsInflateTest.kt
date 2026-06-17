package ru.den.writes.code.project01.cliJvm.cliArgs

import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.CliArgsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CliArgsInflateTest {

    //region happy path

    @Test
    fun `when -inflate with N and -session passed - then Inflate carries both`() {
        // given
        val args = arrayOf("-inflate", "10", "-session", "foo")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val inflate = assertIs<CliArgs.Inflate>(parsed)
        assertEquals("foo", inflate.sessionId)
        assertEquals(10, inflate.n)
    }

    @Test
    fun `when -inflate used with empty API keys - then no key required (pure DB op)`() {
        // given
        // Pure DB op — no LLM call — so it must not require either key.
        val args = arrayOf("-inflate", "3", "-session", "foo")

        // when
        val parsed = CliArgs.from(args, geminiApiKey = "", openRouterApiKey = "")

        // then
        val inflate = assertIs<CliArgs.Inflate>(parsed)
        assertEquals(3, inflate.n)
        assertEquals("foo", inflate.sessionId)
    }
    //endregion

    //region validation

    @Test
    fun `when -inflate used without -session - then MissingRequiredArgument on -session`() {
        // given
        val args = arrayOf("-inflate", "5")

        // when
        val ex = assertFailsWith<CliArgsException.MissingRequiredArgument> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-session", ex.argName)
    }

    @Test
    fun `when -inflate N is non-positive - then InvalidArgumentValue on -inflate`() {
        // given
        val args = arrayOf("-inflate", "0", "-session", "foo")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-inflate", ex.argName)
    }
    //endregion

    //region incompatible flags

    @Test
    fun `when -inflate combined with -prompt - then InvalidArgumentValue on -prompt`() {
        // given
        val args = arrayOf("-inflate", "5", "-session", "foo", "-prompt", "hi")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-prompt", ex.argName)
    }

    @Test
    fun `when -inflate combined with -feedFile - then InvalidArgumentValue on -feedFile`() {
        // given
        val args = arrayOf("-inflate", "5", "-session", "foo", "-feedFile", "/tmp/x")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-feedFile", ex.argName)
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

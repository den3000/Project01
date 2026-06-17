package ru.den.writes.code.project01.cliJvm.cliArgs

import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.CliArgsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CliArgsFeedModeTest {

    //region feedFile happy path

    @Test
    fun `when -feedFile passed without -chunkChars - then default chunk size used`() {
        // given
        val args = arrayOf("-prompt", "hi", "-feedFile", "/tmp/data.txt")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals("/tmp/data.txt", chat.feedFile)
        assertEquals(2500, chat.chunkChars)
        assertEquals("", chat.feedInstruction)
    }

    @Test
    fun `when -feedFile with -chunkChars and -feedInstruction - then all carried through`() {
        // given
        val args = arrayOf(
            "-prompt", "hi",
            "-feedFile", "/tmp/data.txt",
            "-chunkChars", "777",
            "-feedInstruction", "Briefly summarise:",
        )

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals(777, chat.chunkChars)
        assertEquals("Briefly summarise:", chat.feedInstruction)
    }
    //endregion

    //region feedFile conflicts

    @Test
    fun `when -feedFile used with -oneshot - then InvalidArgumentValue on -feedFile`() {
        // given
        val args = arrayOf("-prompt", "hi", "-oneshot", "-feedFile", "/tmp/data.txt")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-feedFile", ex.argName)
    }

    @Test
    fun `when -chunkChars used without -feedFile - then InvalidArgumentValue on -chunkChars`() {
        // given
        val args = arrayOf("-prompt", "hi", "-chunkChars", "1000")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-chunkChars", ex.argName)
    }

    @Test
    fun `when -feedInstruction used without -feedFile - then InvalidArgumentValue on -feedInstruction`() {
        // given
        val args = arrayOf("-prompt", "hi", "-feedInstruction", "go go")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-feedInstruction", ex.argName)
    }

    @Test
    fun `when -chunkChars value is non-positive - then InvalidArgumentValue on -chunkChars`() {
        // given
        val args = arrayOf("-prompt", "hi", "-feedFile", "/tmp/x.txt", "-chunkChars", "0")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-chunkChars", ex.argName)
    }
    //endregion

    //region byLine happy path

    @Test
    fun `when -byLine passed with -feedFile - then line-feed mode set on Chat`() {
        // given
        val args = arrayOf("-prompt", "hi", "-feedFile", "/tmp/x.txt", "-byLine")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertTrue(chat.byLine)
        assertEquals("/tmp/x.txt", chat.feedFile)
    }

    @Test
    fun `when -byLine combined with -feedInstruction - then both carried through`() {
        // given
        val args = arrayOf(
            "-prompt", "hi",
            "-feedFile", "/tmp/x.txt",
            "-byLine",
            "-feedInstruction", "Comment:",
        )

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertTrue(chat.byLine)
        assertEquals("Comment:", chat.feedInstruction)
    }
    //endregion

    //region byLine conflicts

    @Test
    fun `when -byLine used without -feedFile - then InvalidArgumentValue on -byLine`() {
        // given
        val args = arrayOf("-prompt", "hi", "-byLine")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-byLine", ex.argName)
    }

    @Test
    fun `when -byLine and -chunkChars both passed - then InvalidArgumentValue on -chunkChars`() {
        // given
        val args = arrayOf("-prompt", "hi", "-feedFile", "/tmp/x.txt", "-byLine", "-chunkChars", "100")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-chunkChars", ex.argName)
    }

    @Test
    fun `when -byLine used with -oneshot - then InvalidArgumentValue on -byLine`() {
        // given
        val args = arrayOf("-prompt", "hi", "-oneshot", "-byLine")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-byLine", ex.argName)
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

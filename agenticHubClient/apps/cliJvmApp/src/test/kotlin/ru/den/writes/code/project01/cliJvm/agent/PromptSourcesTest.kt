package ru.den.writes.code.project01.cliJvm.agent

import ru.den.writes.code.project01.cliJvm.ChunkedFilePromptSource
import ru.den.writes.code.project01.cliJvm.LineFilePromptSource
import ru.den.writes.code.project01.cliJvm.PromptResult
import java.io.BufferedReader
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptSourcesTest {

    //region ChunkedFilePromptSource

    @Test
    fun `when ChunkedFilePromptSource called repeatedly - then yields successive chunks then Stop`() {
        // given
        val source = ChunkedFilePromptSource(
            reader = StringReader("hello world!!"),
            chunkChars = 5,
            instruction = "",
        )

        // when - then
        // Drain the source: each nextPrompt() peels off the next chunk,
        // and after the buffer is empty we get Stop.
        assertEquals(PromptResult.Prompt("hello"), source.nextPrompt())
        assertEquals(PromptResult.Prompt(" worl"), source.nextPrompt())
        assertEquals(PromptResult.Prompt("d!!"), source.nextPrompt())
        assertEquals(PromptResult.Stop, source.nextPrompt())
    }

    @Test
    fun `when ChunkedFilePromptSource has instruction prefix - then wraps each chunk`() {
        // given
        val source = ChunkedFilePromptSource(
            reader = StringReader("12345"),
            chunkChars = 5,
            instruction = "Comment:",
        )

        // when - then
        assertEquals(PromptResult.Prompt("Comment:\n\n12345"), source.nextPrompt())
        assertEquals(PromptResult.Stop, source.nextPrompt())
    }
    //endregion

    //region LineFilePromptSource

    @Test
    fun `when LineFilePromptSource called - then yields one line per turn`() {
        // given
        val source = LineFilePromptSource(BufferedReader(StringReader("first\nsecond\nthird\n")))

        // when - then
        assertEquals(PromptResult.Prompt("first"), source.nextPrompt())
        assertEquals(PromptResult.Prompt("second"), source.nextPrompt())
        assertEquals(PromptResult.Prompt("third"), source.nextPrompt())
        assertEquals(PromptResult.Stop, source.nextPrompt())
    }

    @Test
    fun `when LineFilePromptSource encounters blank and whitespace-only lines - then skips them`() {
        // given
        val source = LineFilePromptSource(BufferedReader(StringReader("a\n\n   \nb\n")))

        // when - then
        assertEquals(PromptResult.Prompt("a"), source.nextPrompt())
        assertEquals(PromptResult.Prompt("b"), source.nextPrompt())
        assertEquals(PromptResult.Stop, source.nextPrompt())
    }

    @Test
    fun `when LineFilePromptSource has instruction prefix - then trims and wraps each line`() {
        // given
        val source = LineFilePromptSource(
            BufferedReader(StringReader("  hello  \nworld\n")),
            instruction = "Comment:",
        )

        // when - then
        assertEquals(PromptResult.Prompt("Comment:\n\nhello"), source.nextPrompt())
        assertEquals(PromptResult.Prompt("Comment:\n\nworld"), source.nextPrompt())
    }

    @Test
    fun `when LineFilePromptSource notifyTurnFailed called - then next nextPrompt returns Stop`() {
        // given
        val source = LineFilePromptSource(BufferedReader(StringReader("a\nb\nc\n")))

        // when
        assertEquals(PromptResult.Prompt("a"), source.nextPrompt())
        source.notifyTurnFailed()
        val actual = source.nextPrompt()

        // then
        assertEquals(PromptResult.Stop, actual)
        assertTrue(source.terminated)
    }
    //endregion
}

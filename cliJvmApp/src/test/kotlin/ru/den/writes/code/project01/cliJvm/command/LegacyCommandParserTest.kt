package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.cliJvm.ContextStrategyKind
import ru.den.writes.code.project01.shared.llm.ModelProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LegacyCommandParserTest {

    private val parser = LegacyCommandParser(
        ApiKeys(DUMMY_GEMINI_KEY, DUMMY_OPENROUTER_KEY, DUMMY_HUGGINGFACE_KEY),
    )

    //region mode mapping

    @Test
    fun `when -prompt passed - then RunChat with legacy defaults`() {
        // when
        val actual = parser.parse(arrayOf("-prompt", "hi"))

        // then
        val chat = assertIs<CliCommand.RunChat>(actual)
        assertEquals("hi", chat.prompt)
        assertEquals(ContextStrategyKind.FULL, chat.strategy)
        assertEquals(2500, chat.chunkChars)
        assertEquals(6, chat.keepLast)
        assertEquals(10, chat.summarizeEvery)
    }

    @Test
    fun `when -prompt with -oneshot passed - then RunOneShot`() {
        // when
        val actual = parser.parse(arrayOf("-prompt", "hi", "-oneshot"))

        // then
        val one = assertIs<CliCommand.RunOneShot>(actual)
        assertEquals("hi", one.prompt)
    }

    @Test
    fun `when -sessions passed - then ListSessions`() {
        // when - then
        assertEquals(CliCommand.ListSessions, parser.parse(arrayOf("-sessions")))
    }

    @Test
    fun `when -clean passed - then CleanHistory`() {
        // when - then
        assertEquals(CliCommand.CleanHistory, parser.parse(arrayOf("-clean")))
    }

    @Test
    fun `when -inflate with -session passed - then InflateSession carries both`() {
        // when
        val actual = parser.parse(arrayOf("-inflate", "5", "-session", "foo"))

        // then
        assertEquals(CliCommand.InflateSession("foo", 5), actual)
    }

    @Test
    fun `when -memory show passed - then MemoryOp with Show action`() {
        // when
        val actual = parser.parse(arrayOf("-memory", "show"))

        // then
        assertEquals(CliCommand.MemoryOp(MemoryAction.Show), actual)
    }
    //endregion

    //region passthrough

    @Test
    fun `when args are invalid - then CliArgsException propagates`() {
        // when - then
        assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parser.parse(arrayOf("-prompt", "hi", "-provider", "nope"))
        }
    }

    @Test
    fun `when keys provided - then the gemini key lands in the model provider`() {
        // when
        val chat = assertIs<CliCommand.RunChat>(parser.parse(arrayOf("-prompt", "hi")))

        // then
        val gemini = assertIs<ModelProvider.Gemini>(chat.modelProvider)
        assertEquals(DUMMY_GEMINI_KEY, gemini.apiKey)
    }
    //endregion

    private companion object {
        const val DUMMY_GEMINI_KEY = "test-gemini-key"
        const val DUMMY_OPENROUTER_KEY = "test-openrouter-key"
        const val DUMMY_HUGGINGFACE_KEY = "test-huggingface-key"
    }
}

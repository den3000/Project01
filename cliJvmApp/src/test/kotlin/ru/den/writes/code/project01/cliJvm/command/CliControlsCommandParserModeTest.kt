package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.cliJvm.ContextStrategyKind
import ru.den.writes.code.project01.shared.llm.ModelProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** CliControls front: deriving the command MODE from parsed controls. */
class CliControlsCommandParserModeTest {

    private val parser = CliControlsCommandParser(
        ApiKeys(DUMMY_GEMINI_KEY, DUMMY_OPENROUTER_KEY, DUMMY_HUGGINGFACE_KEY),
    )

    @Test
    fun `when -prompt passed - then RunChat with legacy defaults`() {
        // when
        val chat = assertIs<CliCommand.RunChat>(parser.parse(arrayOf("-prompt", "hi")))

        // then
        assertEquals("hi", chat.prompt)
        assertEquals(ContextStrategyKind.FULL, chat.strategy)
        assertEquals(2500, chat.chunkChars)
        assertEquals(6, chat.keepLast)
        assertEquals(10, chat.summarizeEvery)
        assertIs<ModelProvider.Gemini>(chat.modelProvider)
    }

    @Test
    fun `when -prompt with -oneshot passed - then RunOneShot`() {
        // when
        val one = assertIs<CliCommand.RunOneShot>(parser.parse(arrayOf("-prompt", "hi", "-oneshot")))

        // then
        assertEquals("hi", one.prompt)
    }

    @Test
    fun `when a bare -session passed - then ListSessions`() {
        // when - then
        assertEquals(CliCommand.ListSessions, parser.parse(arrayOf("-session")))
    }

    @Test
    fun `when -session clear passed - then CleanHistory`() {
        // when - then
        assertEquals(CliCommand.CleanHistory, parser.parse(arrayOf("-session", "clear")))
    }

    @Test
    fun `when -session clear with a name passed - then CleanSession`() {
        // when - then
        assertEquals(CliCommand.CleanSession("demo"), parser.parse(arrayOf("-session", "clear", "demo")))
    }

    @Test
    fun `when -memory passed - then MemoryOp Show`() {
        // when - then — startup memory layer dump (twin of in-session /memory)
        assertEquals(CliCommand.MemoryOp(MemoryAction.Show), parser.parse(arrayOf("-memory")))
    }

    @Test
    fun `when -inflate with a session passed - then InflateSession carries both`() {
        // when - then
        assertEquals(CliCommand.InflateSession("demo", 5), parser.parse(arrayOf("-inflate", "5", "-session", "demo")))
    }

    @Test
    fun `when no prompt and no admin control - then MissingRequiredArgument on -prompt`() {
        // when
        val ex = assertFailsWith<CliArgsException.MissingRequiredArgument> { parser.parse(emptyArray()) }

        // then
        assertEquals("-prompt", ex.argName)
    }

    private companion object {
        const val DUMMY_GEMINI_KEY = "test-gemini-key"
        const val DUMMY_OPENROUTER_KEY = "test-openrouter-key"
        const val DUMMY_HUGGINGFACE_KEY = "test-huggingface-key"
    }
}

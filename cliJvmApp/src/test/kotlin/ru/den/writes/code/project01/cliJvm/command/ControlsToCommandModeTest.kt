package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.cliJvm.ContextStrategyKind
import ru.den.writes.code.project01.shared.llm.ModelProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** ControlsToCommand: deriving the command MODE from parsed controls. */
class ControlsToCommandModeTest {

    @Test
    fun `when -prompt passed - then RunChat with legacy defaults`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(actual)
        assertEquals("hi", actual.prompt)
        assertEquals(ContextStrategyKind.FULL, actual.strategy)
        assertEquals(2500, actual.chunkChars)
        assertEquals(6, actual.keepLast)
        assertEquals(10, actual.summarizeEvery)
        assertIs<ModelProvider.Gemini>(actual.modelProvider)
    }

    @Test
    fun `when -prompt with -oneshot passed - then RunOneShot`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi -oneshot"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        val oneShot = assertIs<CliCommand.RunOneShot>(actual)
        assertEquals("hi", oneShot.prompt)
    }

    @Test
    fun `when -oneshot carries agent knobs - then RunOneShot keeps model and maxTokens`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt ping -oneshot -agent model gemini-2.5-flash maxTokens 200"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        val oneShot = assertIs<CliCommand.RunOneShot>(actual)
        assertEquals("ping", oneShot.prompt)
        assertEquals(200, oneShot.maxTokens)
        assertIs<ModelProvider.Gemini>(oneShot.modelProvider)
    }

    @Test
    fun `when a bare -session passed - then ListSessions`() {
        // given
        val parser = createCommandsParser()
        val input = "-session"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertEquals(CliCommand.ListSessions, actual)
    }

    @Test
    fun `when -session clear passed - then CleanHistory`() {
        // given
        val parser = createCommandsParser()
        val input = "-session clear"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertEquals(CliCommand.CleanHistory, actual)
    }

    @Test
    fun `when -session clear with a name passed - then CleanSession`() {
        // given
        val parser = createCommandsParser()
        val input = "-session clear demo"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertEquals(CliCommand.CleanSession("demo"), actual)
    }

    @Test
    fun `when -memory passed - then MemoryOp Show`() {
        // given
        val parser = createCommandsParser()
        val input = "-memory"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertEquals(CliCommand.MemoryOp(MemoryAction.Show), actual)
    }

    @Test
    fun `when -inflate with a session passed - then InflateSession carries both`() {
        // given
        val parser = createCommandsParser()
        val input = "-inflate 5 -session demo"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertEquals(CliCommand.InflateSession("demo", 5), actual)
    }

    @Test
    fun `when no prompt and no admin control - then MissingRequiredArgument on -prompt`() {
        // given
        val parser = createCommandsParser()
        val input = ""

        // when
        val ex = assertFailsWith<CliArgsException.MissingRequiredArgument> { parser.parse(input.toArgsArray()) }

        // then
        assertEquals("-prompt", ex.argName)
    }
}

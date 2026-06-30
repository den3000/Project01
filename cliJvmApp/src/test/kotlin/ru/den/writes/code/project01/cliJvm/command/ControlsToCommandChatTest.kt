package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.cliJvm.ContextStrategyKind
import ru.den.writes.code.project01.shared.llm.ModelProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The RunChat / RunOneShot construction from non-agent controls: prompt mode +
 * feedFile / strategy / session-field / tui / mcpServer. Owns the full RunChat
 * defaults; agent-fields are covered in ControlsToCommandAgentTest.
 */
class ControlsToCommandChatTest {

    //region flags (RunChat)
    @Test
    fun `when no config flags are used - then RunChat defaults`() {
        // given
        val parser = createCommandsParser()

        // when
        val actual = parser.parse("-prompt hi".toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(actual)
        assertEquals("hi", actual.prompt)
        assertNull(actual.maxTokens)
        assertNull(actual.stopSequences)
        assertNull(actual.endSequence)
        assertNull(actual.temperature)
        assertEquals("gemini-2.5-flash", assertIs<ModelProvider.Gemini>(actual.modelProvider).modelId)
        assertNull(actual.session)
        assertNull(actual.feedFile)
        assertEquals(2500, actual.chunkChars)
        assertEquals("", actual.feedInstruction)
        assertEquals(false, actual.byLine)
        assertEquals(ContextStrategyKind.FULL, actual.strategy)
        assertEquals(6, actual.keepLast)
        assertEquals(10, actual.summarizeEvery)
        assertNull(actual.task)
        assertNull(actual.profile)
        assertNull(actual.memoryMode)
        assertEquals(0, actual.stageAgents.size)
        assertEquals(false, actual.tui)
        assertEquals(0, actual.judgeAgents.size)
        assertNull(actual.mcpServer)
    }

    @Test
    fun `when chat config flags are set - then they land on the command`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi -session sess " +
            "-feedFile /path chunkChars 5000 feedInstruction \"feed me\" " +
            "-strategy summary keepLast 8 summarizeEvery 12 " +
            "-tui -mcpServer \"mcpLab --serve\""

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(actual)
        assertEquals("sess", actual.session)
        assertEquals("/path", actual.feedFile)
        assertEquals(5000, actual.chunkChars)
        assertEquals("feed me", actual.feedInstruction)
        assertEquals(ContextStrategyKind.SUMMARY, actual.strategy)
        assertEquals(8, actual.keepLast)
        assertEquals(12, actual.summarizeEvery)
        assertEquals(true, actual.tui)
        assertEquals("mcpLab --serve", actual.mcpServer)
        // agent-fields stay default (owned by ControlsToCommandAgentTest)
        assertNull(actual.maxTokens)
        assertNull(actual.profile)
        assertNull(actual.memoryMode)
    }

    @Test
    fun `when alternative feed and strategy forms are used - then they land`() {
        // given
        val parser = createCommandsParser()

        // when
        val byLine = parser.parse("-prompt hi -feedFile /path byLine".toArgsArray())
        val window = parser.parse("-prompt hi -strategy window keepLast 4".toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(byLine)
        assertEquals(true, byLine.byLine)
        assertIs<CliCommand.RunChat>(window)
        assertEquals(ContextStrategyKind.WINDOW, window.strategy)
        assertEquals(4, window.keepLast)
    }

    @Test
    fun `when a prompt mode is selected - then RunChat or RunOneShot`() {
        // given
        val parser = createCommandsParser()

        // when - then
        assertIs<CliCommand.RunChat>(parser.parse("-prompt hi".toArgsArray()))
        assertIs<CliCommand.RunOneShot>(parser.parse("-prompt hi -oneshot".toArgsArray()))
    }

    @Test
    fun `when prompt flags are invalid - then rejected`() {
        // given
        val parser = createCommandsParser()

        // when - then
        assertFailsWith<CliArgsException.MissingRequiredArgument> { parser.parse("".toArgsArray()) }
        val cases = listOf(
            "-nope",                // unknown control
            "-prompt tell me",      // unquoted trailing word
            "-strategy bogus",      // bad enum value (parse-error bridge)
        )
        cases.forEach { input ->
            assertFailsWith<CliArgsException.InvalidArgumentValue>(input) { parser.parse(input.toArgsArray()) }
        }
    }
    //endregion

    //region oneshot (RunOneShot)
    @Test
    fun `when oneshot has no config - then RunOneShot defaults`() {
        // given
        val parser = createCommandsParser()

        // when
        val actual = parser.parse("-prompt hi -oneshot".toArgsArray())

        // then
        val oneShot = assertIs<CliCommand.RunOneShot>(actual)
        assertEquals("hi", oneShot.prompt)
        assertNull(oneShot.maxTokens)
        assertNull(oneShot.stopSequences)
        assertNull(oneShot.endSequence)
        assertNull(oneShot.temperature)
        assertEquals("gemini-2.5-flash", assertIs<ModelProvider.Gemini>(oneShot.modelProvider).modelId)
    }

    @Test
    fun `when oneshot carries generation knobs - then RunOneShot all-set`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi -oneshot " +
            "-agent provider openrouter model deepseek/deepseek-r1:free maxTokens 100 temperature 1.2 stopSequence \"stop1 stop2\" endSequence ###"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        val oneShot = assertIs<CliCommand.RunOneShot>(actual)
        assertEquals("hi", oneShot.prompt)
        assertEquals(100, oneShot.maxTokens)
        assertEquals(listOf("stop1", "stop2"), oneShot.stopSequences)
        assertEquals("###", oneShot.endSequence)
        assertEquals(1.2, oneShot.temperature)
        assertEquals("deepseek/deepseek-r1:free", assertIs<ModelProvider.OpenRouter>(oneShot.modelProvider).modelId)
    }
    //endregion
}

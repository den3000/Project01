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
 * defaults; agent-fields are covered in CliArgsToStartCommandMapperAgentTest.
 */
class CliArgsToStartCommandMapperChatTest {

    //region flags (RunChat)
    @Test
    fun `when no config flags are used - then RunChat defaults`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()

        // when
        val actual = mapper.parse("-prompt hi".toArgsArray())

        // then
        assertIs<StartCommand.RunChat>(actual)
        assertEquals("hi", actual.prompt)
        assertNull(actual.maxTokens)
        assertNull(actual.stopSequences)
        assertNull(actual.endSequence)
        assertNull(actual.temperature)
        assertEquals("gemini-2.5-flash", assertIs<ModelProvider.Gemini>(actual.modelProvider).modelId)
        assertNull(actual.config.session)
        assertNull(actual.config.feedFile)
        assertEquals(2500, actual.config.chunkChars)
        assertEquals("", actual.config.feedInstruction)
        assertEquals(false, actual.config.byLine)
        assertEquals(ContextStrategyKind.FULL, actual.config.strategy)
        assertEquals(6, actual.config.keepLast)
        assertEquals(10, actual.config.summarizeEvery)
        assertNull(actual.config.task)
        assertNull(actual.config.profile)
        assertNull(actual.config.memoryMode)
        assertEquals(0, actual.config.stageAgents.size)
        assertEquals(false, actual.config.tui)
        assertEquals(0, actual.config.judgeAgents.size)
        assertEquals(emptyList<String>(), actual.config.mcpServers)
        assertEquals(0, actual.config.schedules.size)
    }

    @Test
    fun `when chat config flags are set - then they land on the command`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()
        val input = "-prompt hi -session sess " +
            "-feedFile /path chunkChars 5000 feedInstruction \"feed me\" " +
            "-strategy summary keepLast 8 summarizeEvery 12 " +
            "-tui -mcpServer \"mcpLab --serve\""

        // when
        val actual = mapper.parse(input.toArgsArray())

        // then
        assertIs<StartCommand.RunChat>(actual)
        assertEquals("sess", actual.config.session)
        assertEquals("/path", actual.config.feedFile)
        assertEquals(5000, actual.config.chunkChars)
        assertEquals("feed me", actual.config.feedInstruction)
        assertEquals(ContextStrategyKind.SUMMARY, actual.config.strategy)
        assertEquals(8, actual.config.keepLast)
        assertEquals(12, actual.config.summarizeEvery)
        assertEquals(true, actual.config.tui)
        assertEquals(listOf("mcpLab --serve"), actual.config.mcpServers)
        // agent-fields stay default (owned by CliArgsToStartCommandMapperAgentTest)
        assertNull(actual.maxTokens)
        assertNull(actual.config.profile)
        assertNull(actual.config.memoryMode)
    }

    @Test
    fun `when alternative feed and strategy forms are used - then they land`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()

        // when
        val byLine = mapper.parse("-prompt hi -feedFile /path byLine".toArgsArray())
        val window = mapper.parse("-prompt hi -strategy window keepLast 4".toArgsArray())

        // then
        assertIs<StartCommand.RunChat>(byLine)
        assertEquals(true, byLine.config.byLine)
        assertIs<StartCommand.RunChat>(window)
        assertEquals(ContextStrategyKind.WINDOW, window.config.strategy)
        assertEquals(4, window.config.keepLast)
    }

    @Test
    fun `when a prompt mode is selected - then RunChat or RunOneShot`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()

        // when - then
        assertIs<StartCommand.RunChat>(mapper.parse("-prompt hi".toArgsArray()))
        assertIs<StartCommand.RunOneShot>(mapper.parse("-prompt hi -oneshot".toArgsArray()))
    }

    @Test
    fun `when prompt flags are invalid - then rejected`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()

        // when - then
        assertFailsWith<CliArgsException.MissingRequiredArgument> { mapper.parse("".toArgsArray()) }
        val cases = listOf(
            "-nope",                // unknown control
            "-prompt tell me",      // unquoted trailing word
            "-strategy bogus",      // bad enum value (parse-error bridge)
        )
        cases.forEach { input ->
            assertFailsWith<CliArgsException.InvalidArgumentValue>(input) { mapper.parse(input.toArgsArray()) }
        }
    }

    @Test
    fun `when multiple mcpServer flags are used - then they accumulate into the list`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()

        // when
        val actual = mapper.parse("-prompt hi -mcpServer \"lab --serve\" -mcpServer \"docs --serve\"".toArgsArray())

        // then
        assertIs<StartCommand.RunChat>(actual)
        assertEquals(listOf("lab --serve", "docs --serve"), actual.config.mcpServers)
    }
    //endregion

    //region oneshot (RunOneShot)
    @Test
    fun `when oneshot has no config - then RunOneShot defaults`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()

        // when
        val actual = mapper.parse("-prompt hi -oneshot".toArgsArray())

        // then
        val oneShot = assertIs<StartCommand.RunOneShot>(actual)
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
        val mapper = createCliArgsToStartCommandMapper()
        val input = "-prompt hi -oneshot " +
            "-agent provider openrouter model deepseek/deepseek-r1:free maxTokens 100 temperature 1.2 stopSequence \"stop1 stop2\" endSequence ###"

        // when
        val actual = mapper.parse(input.toArgsArray())

        // then
        val oneShot = assertIs<StartCommand.RunOneShot>(actual)
        assertEquals("hi", oneShot.prompt)
        assertEquals(100, oneShot.maxTokens)
        assertEquals(listOf("stop1", "stop2"), oneShot.stopSequences)
        assertEquals("###", oneShot.endSequence)
        assertEquals(1.2, oneShot.temperature)
        assertEquals("deepseek/deepseek-r1:free", assertIs<ModelProvider.OpenRouter>(oneShot.modelProvider).modelId)
    }
    //endregion
}

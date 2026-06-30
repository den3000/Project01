package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.TaskBinding
import ru.den.writes.code.project01.shared.memory.TaskStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** ControlsToCommand: partitioning `agent` controls into primary / stage / judge. */
class ControlsToCommandAgentTest {

    //region agent
    @Test
    fun `when no agent flag used - then it is parsed to defaults`() {
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

        val modelProvider = assertIs<ModelProvider.Gemini>(actual.modelProvider)
        assertEquals("gemini-2.5-flash", modelProvider.modelId)

        assertNull(actual.session)
        assertNull(actual.feedFile)
        assertEquals(2500, actual.chunkChars)
        assertEquals("", actual.feedInstruction)
        assertEquals(false, actual.byLine)
        assertEquals(ru.den.writes.code.project01.cliJvm.ContextStrategyKind.FULL, actual.strategy)
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
    fun `when all prompt and agent configuration flags are set to custom values - then they land on the command`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt \"my custom prompt\" " +
            "-agent main provider openrouter model deepseek/deepseek-r1:free maxTokens 100 temperature 1.2 stopSequence \"stop1 stop2\" endSequence ### mode system profile coder " +
            "-session mysession " +
            "-feedFile /path/to/feed chunkChars 5000 feedInstruction \"feed me\" " +
            "-strategy summary keepLast 8 summarizeEvery 12 " +
            "-task mytask " +
            "-tui " +
            "-mcpServer \"mcpLab --serve\""

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(actual)
        assertEquals("my custom prompt", actual.prompt)
        assertEquals(100, actual.maxTokens)
        assertEquals(listOf("stop1", "stop2"), actual.stopSequences)
        assertEquals("###", actual.endSequence)
        assertEquals(1.2, actual.temperature)

        val modelProvider = assertIs<ModelProvider.OpenRouter>(actual.modelProvider)
        assertEquals("deepseek/deepseek-r1:free", modelProvider.modelId)

        assertEquals("mysession", actual.session)
        assertEquals("/path/to/feed", actual.feedFile)
        assertEquals(5000, actual.chunkChars)
        assertEquals("feed me", actual.feedInstruction)
        assertEquals(false, actual.byLine)
        assertEquals(ru.den.writes.code.project01.cliJvm.ContextStrategyKind.SUMMARY, actual.strategy)
        assertEquals(8, actual.keepLast)
        assertEquals(12, actual.summarizeEvery)
        assertEquals("mytask", actual.task)
        assertEquals("coder", actual.profile)
        assertEquals(MemoryMode.SYSTEM, actual.memoryMode)
        assertEquals(true, actual.tui)
        assertEquals("mcpLab --serve", actual.mcpServer)
    }

    @Test
    fun `when agent has invalid params - then throws InvalidArgumentValue`() {
        // given
        val parser = createCommandsParser()
        val cases = listOf(
            "-prompt hi -agent main maxTokens -5",
            "-prompt hi -agent main maxTokens abc",
            "-prompt hi -agent main temperature 2.5",
            "-prompt hi -agent main provider unknown",
            "-prompt hi -agent main -agent secondary",
            "-prompt hi -agent main mode system -agent s stages planning..abc provider gemini model x",
            "-prompt hi -agent main mode system -agent s stages execution..planning provider gemini model x"
        )

        // when - then
        cases.forEach { input ->
            assertFailsWith<CliArgsException.InvalidArgumentValue>(input) {
                parser.parse(input.toArgsArray())
            }
        }

        assertFailsWith<CliArgsException.TooManyValues> {
            parser.parse("-prompt hi -agent main stopSequence \"a b c d e f\"".toArgsArray())
        }
    }

    @Test
    fun `when stage and judge agents span the run - then primary mode plus two stage agents and a judge land`() {
        // given — the full multi-agent demo: stages/judge are -agent subs, memory mode is the primary's mode
        val parser = createCommandsParser()
        val input = "-prompt go -session demo -task auth " +
            "-agent provider gemini model gemini-2.5-flash mode system " +
            "-agent provider gemini model gemini-2.5-pro profile interviewer stages clarification..planning " +
            "-agent provider gemini model gemini-2.5-flash profile coder stages execution..done " +
            "-agent provider openrouter model deepseek/deepseek-r1:free judge stages clarification..done"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(actual)
        assertEquals(MemoryMode.SYSTEM, actual.memoryMode)
        assertEquals("auth", actual.task)
        
        assertEquals(2, actual.stageAgents.size)
        assertEquals("interviewer", actual.stageAgents[0].profileName)
        assertEquals(TaskBinding(TaskStage.CLARIFICATION, TaskStage.PLANNING), actual.stageAgents[0].binding)
        assertEquals("gemini-2.5-pro", actual.stageAgents[0].provider.modelId)
        
        assertEquals("coder", actual.stageAgents[1].profileName)
        assertEquals(TaskBinding(TaskStage.EXECUTION, TaskStage.DONE), actual.stageAgents[1].binding)
        assertEquals("gemini-2.5-flash", actual.stageAgents[1].provider.modelId)

        assertEquals(1, actual.judgeAgents.size)
        val judge = actual.judgeAgents.single()
        assertEquals(TaskBinding(TaskStage.CLARIFICATION, TaskStage.DONE), judge.binding)
        val openRouter = assertIs<ModelProvider.OpenRouter>(judge.provider)
        assertEquals("deepseek/deepseek-r1:free", openRouter.modelId)
    }

    @Test
    fun `when stage or judge configuration violates constraints - then rejected`() {
        // given
        val parser = createCommandsParser()
        val cases = listOf(
            "-prompt hi -agent a -agent b",
            "-prompt hi -agent s stages execution..execution provider gemini model x",
            "-prompt hi -agent main mode system -agent j judge stages execution..execution provider gemini model y",
            "-prompt hi -agent main mode system -agent s stages execution..execution -agent j judge provider gemini model y",
            "-prompt hi -agent main mode system -agent s stages execution..execution -agent j judge stages execution..execution profile coder provider gemini model y"
        )

        // when - then
        cases.forEach { input ->
            assertFailsWith<CliArgsException.InvalidArgumentValue>(input) {
                parser.parse(input.toArgsArray())
            }
        }
    }
}

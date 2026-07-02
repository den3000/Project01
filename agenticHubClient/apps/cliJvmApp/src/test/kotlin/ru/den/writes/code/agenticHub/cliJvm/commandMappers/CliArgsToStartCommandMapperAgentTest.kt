package ru.den.writes.code.agenticHub.cliJvm.commandMappers

import ru.den.writes.code.agenticHub.cliJvm.cliargs.ParseError

import ru.den.writes.code.agenticHub.features.lifecycle.session.SessionCommand
import ru.den.writes.code.agenticHub.features.memory.ContextStrategyKind
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.agent.memory.MemoryMode
import ru.den.writes.code.agenticHub.features.agent.memory.TaskBinding
import ru.den.writes.code.agenticHub.features.agent.memory.TaskStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** CliArgsToStartCommandMapper: partitioning `agent` controls into primary / stage / judge. */
class CliArgsToStartCommandMapperAgentTest {

    //region flags

    @Test
    fun `when no agent flag used - then it is parsed to defaults`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()

        // when
        val actual = mapper.parseOk("-prompt hi".toArgsArray())

        // then
        assertIs<StartCommand.RunChat>(actual)
        assertEquals("hi", actual.prompt)
        // agent-fields default (the full RunChat defaults live in CliArgsToStartCommandMapperChatTest)
        assertNull(actual.maxTokens)
        assertNull(actual.stopSequences)
        assertNull(actual.endSequence)
        assertNull(actual.temperature)
        assertEquals("gemini-2.5-flash", assertIs<ModelProvider.Gemini>(actual.modelProvider).modelId)
        assertNull(actual.config.profile)
        assertNull(actual.config.memoryMode)
        assertEquals(0, actual.config.stageAgents.size)
        assertEquals(0, actual.config.judgeAgents.size)
    }

    @Test
    fun `when primary agent is configured with custom values - then they land on the command`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()
        val input = "-prompt hi " +
            "-agent main provider openrouter model deepseek/deepseek-r1:free maxTokens 100 temperature 1.2 stopSequence \"stop1 stop2\" endSequence ### mode system profile coder"

        // when
        val actual = mapper.parseOk(input.toArgsArray())

        // then
        assertIs<StartCommand.RunChat>(actual)
        assertEquals("hi", actual.prompt)
        assertEquals(100, actual.maxTokens)
        assertEquals(listOf("stop1", "stop2"), actual.stopSequences)
        assertEquals("###", actual.endSequence)
        assertEquals(1.2, actual.temperature)

        val modelProvider = assertIs<ModelProvider.OpenRouter>(actual.modelProvider)
        assertEquals("deepseek/deepseek-r1:free", modelProvider.modelId)

        assertEquals("coder", actual.config.profile)
        assertEquals(MemoryMode.SYSTEM, actual.config.memoryMode)

        // Session defaults (not configured here)
        assertNull(actual.config.session)
        assertNull(actual.config.feedFile)
        assertEquals(2500, actual.config.chunkChars)
        assertEquals("", actual.config.feedInstruction)
        assertEquals(false, actual.config.byLine)
        assertEquals(ContextStrategyKind.FULL, actual.config.strategy)
        assertEquals(6, actual.config.keepLast)
        assertEquals(10, actual.config.summarizeEvery)
        assertNull(actual.config.task)
        assertEquals(false, actual.config.tui)
        assertEquals(emptyList<String>(), actual.config.mcpServers)
        assertEquals(0, actual.config.schedules.size)
    }

    @Test
    fun `when agent has invalid params - then throws InvalidArgumentValue`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()
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
            mapper.assertInvalid(input.toArgsArray(), input)
        }

        assertIs<ParseError.TooManyValues>(
            mapper.parseErr("-prompt hi -agent main stopSequence \"a b c d e f\"".toArgsArray()),
        )
    }

    @Test
    fun `when stage and judge agents span the run - then primary mode plus two stage agents and a judge land`() {
        // given — the full multi-agent demo: stages/judge are -agent subs, memory mode is the primary's mode
        val mapper = createCliArgsToStartCommandMapper()
        val input = "-prompt go -session demo -task auth " +
            "-agent provider gemini model gemini-2.5-flash mode system " +
            "-agent provider gemini model gemini-2.5-pro profile interviewer stages clarification..planning " +
            "-agent provider gemini model gemini-2.5-flash profile coder stages execution..done " +
            "-agent provider openrouter model deepseek/deepseek-r1:free judge stages clarification..done"

        // when
        val actual = mapper.parseOk(input.toArgsArray())

        // then
        assertIs<StartCommand.RunChat>(actual)
        assertEquals(MemoryMode.SYSTEM, actual.config.memoryMode)
        assertEquals("auth", actual.config.task)
        
        assertEquals(2, actual.config.stageAgents.size)
        assertEquals("interviewer", actual.config.stageAgents[0].profileName)
        assertEquals(TaskBinding(TaskStage.CLARIFICATION, TaskStage.PLANNING), actual.config.stageAgents[0].binding)
        assertEquals("gemini-2.5-pro", actual.config.stageAgents[0].provider.modelId)
        
        assertEquals("coder", actual.config.stageAgents[1].profileName)
        assertEquals(TaskBinding(TaskStage.EXECUTION, TaskStage.DONE), actual.config.stageAgents[1].binding)
        assertEquals("gemini-2.5-flash", actual.config.stageAgents[1].provider.modelId)

        assertEquals(1, actual.config.judgeAgents.size)
        val judge = actual.config.judgeAgents.single()
        assertEquals(TaskBinding(TaskStage.CLARIFICATION, TaskStage.DONE), judge.binding)
        val openRouter = assertIs<ModelProvider.OpenRouter>(judge.provider)
        assertEquals("deepseek/deepseek-r1:free", openRouter.modelId)
    }

    @Test
    fun `when stage or judge configuration violates constraints - then rejected`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()
        val cases = listOf(
            "-prompt hi -agent a -agent b",
            "-prompt hi -agent s stages execution..execution provider gemini model x",
            "-prompt hi -agent main mode system -agent j judge stages execution..execution provider gemini model y",
            "-prompt hi -agent main mode system -agent s stages execution..execution -agent j judge provider gemini model y",
            "-prompt hi -agent main mode system -agent s stages execution..execution -agent j judge stages execution..execution profile coder provider gemini model y"
        )

        // when - then
        cases.forEach { input ->
            mapper.assertInvalid(input.toArgsArray(), input)
        }
    }
    //endregion

    //region commands
    @Test
    fun `when agent command used - then it behaves accordingly`() {
        // given
        val mapper = createCliArgToSessionCommandMapper()
        val cases = listOf(
            "/agent mode system" to SessionCommand.SetMemoryMode(MemoryMode.SYSTEM),
            "/agent mode preamble" to SessionCommand.SetMemoryMode(MemoryMode.PREAMBLE),
            // Unsuccessful or unsupported in-session agent ops map to null (fall through as normal prompts)
            "/agent mode loud" to null,
            "/agent mode none" to null,
            "/agent show" to null,
            "/agent main" to null,
            // generic non-commands fall through to a normal prompt
            "/nope" to null,
            "hello there" to null,
        )

        // when - then
        cases.forEach { (input, expected) ->
            assertEquals(expected, mapper.parse(input), input)
        }
    }
    //endregion
}

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

    @Test
    fun `when the primary agent names a provider and model - then modelProvider resolves`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi -agent main provider openrouter model deepseek/deepseek-r1:free"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(actual)
        val modelProvider = assertIs<ModelProvider.OpenRouter>(actual.modelProvider)
        assertEquals("deepseek/deepseek-r1:free", modelProvider.modelId)
    }

    @Test
    fun `when no agent is given - then the default gemini provider is used`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(actual)
        assertIs<ModelProvider.Gemini>(actual.modelProvider)
        assertNull(actual.stageAgents.firstOrNull())
    }

    @Test
    fun `when the primary agent carries knobs - then they land on the command`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi -agent main maxTokens 42 temperature 0.5"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(actual)
        assertEquals(42, actual.maxTokens)
        assertEquals(0.5, actual.temperature)
    }

    @Test
    fun `when the primary agent sets mode and profile - then memoryMode and profile land`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi -agent main mode system profile coder"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(actual)
        assertEquals(MemoryMode.SYSTEM, actual.memoryMode)
        assertEquals("coder", actual.profile)
    }

    @Test
    fun `when an agent has a stages span - then it becomes a stage agent`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi -agent main mode system " +
            "-agent planner stages planning..execution provider gemini model gemini-2.5-pro profile p"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(actual)
        val spec = actual.stageAgents.single()
        assertEquals(TaskBinding(TaskStage.PLANNING, TaskStage.EXECUTION), spec.binding)
        assertEquals("gemini-2.5-pro", spec.provider.modelId)
        assertEquals("p", spec.profileName)
    }

    @Test
    fun `when an agent has a judge flag - then it becomes a judge`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi -agent main mode system " +
            "-agent s stages clarification..clarification provider gemini model x " +
            "-agent j judge stages execution..execution provider gemini model y"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(actual)
        assertEquals(TaskBinding(TaskStage.EXECUTION, TaskStage.EXECUTION), actual.judgeAgents.single().binding)
    }

    @Test
    fun `when stage and judge agents span the run - then primary mode plus two stage agents and a judge land`() {
        // given — the full multi-agent demo: stages/judge are -agent subs, memory mode is the primary's mode
        val parser = createCommandsParser()
        val input = "-prompt go -session demo -task auth " +
            "-agent provider gemini model gemini-2.5-flash mode system " +
            "-agent provider gemini model gemini-2.5-flash profile interviewer stages clarification..planning " +
            "-agent provider gemini model gemini-2.5-flash profile coder stages execution..done " +
            "-agent provider gemini model gemini-2.5-flash judge stages clarification..done"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(actual)
        assertEquals(MemoryMode.SYSTEM, actual.memoryMode)
        assertEquals("auth", actual.task)
        assertEquals(2, actual.stageAgents.size)
        assertEquals("interviewer", actual.stageAgents[0].profileName)
        assertEquals(TaskBinding(TaskStage.CLARIFICATION, TaskStage.PLANNING), actual.stageAgents[0].binding)
        assertEquals("coder", actual.stageAgents[1].profileName)
        assertEquals(TaskBinding(TaskStage.EXECUTION, TaskStage.DONE), actual.stageAgents[1].binding)
        assertEquals(TaskBinding(TaskStage.CLARIFICATION, TaskStage.DONE), actual.judgeAgents.single().binding)
    }

    @Test
    fun `when two agents lack stages and judge - then more than one primary is rejected`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi -agent a -agent b"

        // when - then
        assertFailsWith<CliArgsException.InvalidArgumentValue> { parser.parse(input.toArgsArray()) }
    }

    @Test
    fun `when a stage agent has no memory mode - then rejected`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi -agent s stages execution..execution provider gemini model x"

        // when - then
        assertFailsWith<CliArgsException.InvalidArgumentValue> { parser.parse(input.toArgsArray()) }
    }

    @Test
    fun `when a judge has no stage agent - then rejected`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi -agent main mode system -agent j judge stages execution..execution provider gemini model y"

        // when - then
        assertFailsWith<CliArgsException.InvalidArgumentValue> { parser.parse(input.toArgsArray()) }
    }
}

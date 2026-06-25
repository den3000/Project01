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

/** CliControls front: partitioning `agent` controls into primary / stage / judge. */
class CliControlsCommandParserAgentTest {

    private val parser = CliControlsCommandParser(
        ApiKeys(DUMMY_GEMINI_KEY, DUMMY_OPENROUTER_KEY, DUMMY_HUGGINGFACE_KEY),
    )

    @Test
    fun `when the primary agent names a provider and model - then modelProvider resolves`() {
        // when
        val chat = chat("-prompt", "hi", "-agent", "main", "provider", "openrouter", "model", "deepseek/deepseek-r1:free")

        // then
        val or = assertIs<ModelProvider.OpenRouter>(chat.modelProvider)
        assertEquals("deepseek/deepseek-r1:free", or.modelId)
    }

    @Test
    fun `when no agent is given - then the default gemini provider is used`() {
        // when
        val chat = chat("-prompt", "hi")

        // then
        assertIs<ModelProvider.Gemini>(chat.modelProvider)
        assertNull(chat.stageAgents.firstOrNull())
    }

    @Test
    fun `when the primary agent carries knobs - then they land on the command`() {
        // when
        val chat = chat("-prompt", "hi", "-agent", "main", "maxTokens", "42", "temperature", "0.5")

        // then
        assertEquals(42, chat.maxTokens)
        assertEquals(0.5, chat.temperature)
    }

    @Test
    fun `when the primary agent sets mode and profile - then memoryMode and profile land`() {
        // when
        val chat = chat("-prompt", "hi", "-agent", "main", "mode", "system", "profile", "coder")

        // then
        assertEquals(MemoryMode.SYSTEM, chat.memoryMode)
        assertEquals("coder", chat.profile)
    }

    @Test
    fun `when an agent has a stages span - then it becomes a stage agent`() {
        // when
        val chat = chat(
            "-prompt", "hi", "-agent", "main", "mode", "system",
            "-agent", "planner", "stages", "planning..execution", "provider", "gemini", "model", "gemini-2.5-pro", "profile", "p",
        )

        // then
        val spec = chat.stageAgents.single()
        assertEquals(TaskBinding(TaskStage.PLANNING, TaskStage.EXECUTION), spec.binding)
        assertEquals("gemini-2.5-pro", spec.provider.modelId)
        assertEquals("p", spec.profileName)
    }

    @Test
    fun `when an agent has a judge flag - then it becomes a judge`() {
        // when
        val chat = chat(
            "-prompt", "hi", "-agent", "main", "mode", "system",
            "-agent", "s", "stages", "clarification..clarification", "provider", "gemini", "model", "x",
            "-agent", "j", "judge", "stages", "execution..execution", "provider", "gemini", "model", "y",
        )

        // then
        assertEquals(TaskBinding(TaskStage.EXECUTION, TaskStage.EXECUTION), chat.judgeAgents.single().binding)
    }

    @Test
    fun `when two agents lack stages and judge - then more than one primary is rejected`() {
        // when - then
        assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parser.parse(arrayOf("-prompt", "hi", "-agent", "a", "-agent", "b"))
        }
    }

    @Test
    fun `when a stage agent has no memory mode - then rejected`() {
        // when - then
        assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parser.parse(arrayOf("-prompt", "hi", "-agent", "s", "stages", "execution..execution", "provider", "gemini", "model", "x"))
        }
    }

    @Test
    fun `when a judge has no stage agent - then rejected`() {
        // when - then
        assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parser.parse(arrayOf("-prompt", "hi", "-agent", "main", "mode", "system", "-agent", "j", "judge", "stages", "execution..execution", "provider", "gemini", "model", "y"))
        }
    }

    private fun chat(vararg args: String): CliCommand.RunChat = assertIs<CliCommand.RunChat>(parser.parse(arrayOf(*args)))

    private companion object {
        const val DUMMY_GEMINI_KEY = "test-gemini-key"
        const val DUMMY_OPENROUTER_KEY = "test-openrouter-key"
        const val DUMMY_HUGGINGFACE_KEY = "test-huggingface-key"
    }
}

package ru.den.writes.code.project01.cliJvm.cliArgs

import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.shared.memory.TaskBinding
import ru.den.writes.code.project01.shared.memory.TaskStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Parsing of the repeatable `-stageAgent <from..to>=<provider>:<model>[@<profile>]`
 * flag: each occurrence becomes a StageAgentSpec; model ids keep their slashes
 * and colons; an `@profile` suffix is peeled off; and the flag is rejected
 * without -memory-mode or in non-chat modes.
 */
class CliArgsStageAgentTest {

    //region parsing

    @Test
    fun `when one -stageAgent given - then parsed into a single spec`() {
        // given
        val args = arrayOf(
            "-prompt", "hi", "-memory-mode", "system",
            "-stageAgent", "planning..execution=gemini:gemini-2.5-pro@planner",
        )

        // when
        val chat = assertIs<CliArgs.Chat>(parseCliArgsWithDummyKeys(*args))

        // then
        val spec = chat.stageAgents.single()
        assertEquals(TaskBinding(TaskStage.PLANNING, TaskStage.EXECUTION), spec.binding)
        assertEquals("gemini-2.5-pro", spec.provider.modelId)
        assertEquals("planner", spec.profileName)
    }

    @Test
    fun `when -stageAgent repeated - then all specs kept in order`() {
        // given
        val args = arrayOf(
            "-prompt", "hi", "-memory-mode", "system",
            "-stageAgent", "clarification=gemini:gemini-2.5-flash",
            "-stageAgent", "execution..done=openrouter:openrouter/auto",
        )

        // when
        val chat = assertIs<CliArgs.Chat>(parseCliArgsWithDummyKeys(*args))

        // then
        assertEquals(2, chat.stageAgents.size)
        assertEquals(TaskStage.CLARIFICATION, chat.stageAgents[0].binding.from)
        assertEquals(TaskStage.DONE, chat.stageAgents[1].binding.to)
    }

    @Test
    fun `when a single stage is given - then from equals to`() {
        // given
        val args = arrayOf(
            "-prompt", "hi", "-memory-mode", "system",
            "-stageAgent", "validation=gemini:gemini-2.5-flash",
        )

        // when
        val chat = assertIs<CliArgs.Chat>(parseCliArgsWithDummyKeys(*args))

        // then
        assertEquals(
            TaskBinding(TaskStage.VALIDATION, TaskStage.VALIDATION),
            chat.stageAgents.single().binding,
        )
    }

    @Test
    fun `when the model id carries slashes and colons - then it is preserved verbatim`() {
        // given
        val args = arrayOf(
            "-prompt", "hi", "-memory-mode", "system",
            "-stageAgent", "execution=openrouter:deepseek/deepseek-r1:free",
        )

        // when
        val chat = assertIs<CliArgs.Chat>(parseCliArgsWithDummyKeys(*args))

        // then
        val spec = chat.stageAgents.single()
        assertIs<ModelProvider.OpenRouter>(spec.provider)
        assertEquals("deepseek/deepseek-r1:free", spec.provider.modelId)
        assertNull(spec.profileName)
    }

    @Test
    fun `when there is no profile suffix - then profileName is null and the model is whole`() {
        // given
        val args = arrayOf(
            "-prompt", "hi", "-memory-mode", "system",
            "-stageAgent", "planning=huggingface:meta-llama/Llama-3.3-70B-Instruct",
        )

        // when
        val chat = assertIs<CliArgs.Chat>(parseCliArgsWithDummyKeys(*args))

        // then
        val spec = chat.stageAgents.single()
        assertEquals("meta-llama/Llama-3.3-70B-Instruct", spec.provider.modelId)
        assertNull(spec.profileName)
    }
    //endregion

    //region validation

    @Test
    fun `when -stageAgent given without -memory-mode - then rejected`() {
        // given
        val args = arrayOf("-prompt", "hi", "-stageAgent", "planning=gemini:gemini-2.5-flash")

        // when - then
        assertFailsWith<CliArgsException.InvalidArgumentValue> { parseCliArgsWithDummyKeys(*args) }
    }

    @Test
    fun `when -stageAgent combined with -oneshot - then rejected`() {
        // given
        val args = arrayOf("-prompt", "hi", "-oneshot", "-stageAgent", "planning=gemini:gemini-2.5-flash")

        // when - then
        assertFailsWith<CliArgsException.InvalidArgumentValue> { parseCliArgsWithDummyKeys(*args) }
    }

    @Test
    fun `when the stage range is reversed - then rejected`() {
        // given
        val args = arrayOf(
            "-prompt", "hi", "-memory-mode", "system",
            "-stageAgent", "execution..planning=gemini:gemini-2.5-flash",
        )

        // when - then
        assertFailsWith<CliArgsException.InvalidArgumentValue> { parseCliArgsWithDummyKeys(*args) }
    }

    @Test
    fun `when the stage keyword is unknown - then rejected`() {
        // given
        val args = arrayOf(
            "-prompt", "hi", "-memory-mode", "system",
            "-stageAgent", "shipping=gemini:gemini-2.5-flash",
        )

        // when - then
        assertFailsWith<CliArgsException.InvalidArgumentValue> { parseCliArgsWithDummyKeys(*args) }
    }

    @Test
    fun `when the provider is unknown - then rejected`() {
        // given
        val args = arrayOf(
            "-prompt", "hi", "-memory-mode", "system",
            "-stageAgent", "planning=bogus:some-model",
        )

        // when - then
        assertFailsWith<CliArgsException.InvalidArgumentValue> { parseCliArgsWithDummyKeys(*args) }
    }
    //endregion

    private fun parseCliArgsWithDummyKeys(vararg args: String): CliArgs =
        CliArgs.from(
            args = arrayOf(*args),
            geminiApiKey = DUMMY_GEMINI_KEY,
            openRouterApiKey = DUMMY_OPENROUTER_KEY,
            huggingFaceApiKey = DUMMY_HUGGINGFACE_KEY,
        )

    private companion object {
        const val DUMMY_GEMINI_KEY = "test-gemini-key"
        const val DUMMY_OPENROUTER_KEY = "test-openrouter-key"
        const val DUMMY_HUGGINGFACE_KEY = "test-huggingface-key"
    }
}

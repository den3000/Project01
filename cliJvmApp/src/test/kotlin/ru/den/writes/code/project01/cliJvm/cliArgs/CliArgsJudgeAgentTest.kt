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

class CliArgsJudgeAgentTest {

    // -judgeAgent needs a stage agent (which itself needs -memory-mode).
    private fun baseArgs(vararg extra: String): Array<String> = arrayOf(
        "-prompt", "hi",
        "-memory-mode", "system",
        "-stageAgent", "clarification..done=gemini:gemini-2.5-flash",
        *extra,
    )

    //region parsing

    @Test
    fun `when -judgeAgent given with a stage agent - then it parses to a StageJudgeSpec`() {
        // given
        val args = baseArgs("-judgeAgent", "clarification..planning=gemini:gemini-2.5-flash")

        // when
        val chat = assertIs<CliArgs.Chat>(parseCliArgsWithDummyKeys(*args))

        // then
        val spec = chat.judgeAgents.single()
        assertEquals(TaskBinding(TaskStage.CLARIFICATION, TaskStage.PLANNING), spec.binding)
        val provider = assertIs<ModelProvider.Gemini>(spec.provider)
        assertEquals("gemini-2.5-flash", provider.modelId)
    }

    @Test
    fun `when -judgeAgent uses a single stage - then from equals to`() {
        // given
        val args = baseArgs("-judgeAgent", "execution=gemini:gemini-2.5-flash")

        // when
        val chat = assertIs<CliArgs.Chat>(parseCliArgsWithDummyKeys(*args))

        // then
        assertEquals(TaskBinding(TaskStage.EXECUTION, TaskStage.EXECUTION), chat.judgeAgents.single().binding)
    }

    @Test
    fun `when -judgeAgent repeated - then each becomes a spec`() {
        // given
        val args = baseArgs(
            "-judgeAgent", "clarification..planning=gemini:gemini-2.5-flash",
            "-judgeAgent", "execution..done=gemini:gemini-2.5-flash-lite",
        )

        // when
        val chat = assertIs<CliArgs.Chat>(parseCliArgsWithDummyKeys(*args))

        // then
        assertEquals(2, chat.judgeAgents.size)
    }
    //endregion

    //region rejection

    @Test
    fun `when -judgeAgent without any -stageAgent - then InvalidArgumentValue on -judgeAgent`() {
        // given — a judge needs at least one stage agent to pair with
        val args = arrayOf(
            "-prompt", "hi", "-memory-mode", "system",
            "-judgeAgent", "clarification..done=gemini:gemini-2.5-flash",
        )

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-judgeAgent", ex.argName)
    }

    @Test
    fun `when -judgeAgent used with -oneshot - then InvalidArgumentValue on -judgeAgent`() {
        // given
        val args = arrayOf(
            "-prompt", "hi", "-oneshot",
            "-judgeAgent", "clarification..done=gemini:gemini-2.5-flash",
        )

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-judgeAgent", ex.argName)
    }

    @Test
    fun `when -judgeAgent carries an @profile - then rejected`() {
        // given — a judge has no persona
        val args = baseArgs("-judgeAgent", "clarification..planning=gemini:gemini-2.5-flash@coder")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-judgeAgent", ex.argName)
    }

    @Test
    fun `when -judgeAgent names an unknown provider - then rejected`() {
        // given
        val args = baseArgs("-judgeAgent", "clarification..planning=nope:some-model")

        // when - then
        assertFailsWith<CliArgsException> {
            parseCliArgsWithDummyKeys(*args)
        }
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

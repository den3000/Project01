package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.cliJvm.memory.MemoryStore
import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.shared.llm.gemini.GeminiModel
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.TaskNotes
import ru.den.writes.code.project01.shared.memory.TaskStage
import java.io.BufferedReader
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Agent-level behaviour of the task state machine: stages auto-advance from a
 * `[[stage:<next>]]` marker in the model's reply (validated against the
 * transition table), pause holds the stage, and a new task starts at the
 * initial stage. Kept separate from [AgentMemoryTest] (profile / rules /
 * memory-mode) to stay under the test-per-file limit.
 */
class AgentTaskStateTest {

    //region auto-advance from the model reply

    @Test
    fun `when reply signals a legal next stage - then the task auto-advances`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                // given
                val memStore = MemoryStore(root).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.CLARIFICATION))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fake = FakeLlmApi().apply { queueText("Requirements confirmed.\n[[stage:planning]]") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")

                // when
                SessionLoop(
                    newChat(prompt = "hi", session = "demo"),
                    fake, store,
                    promptSource = stdinSource("/exit\n"),
                    memory = memory,
                ).run()

                // then
                assertEquals(TaskStage.PLANNING, memStore.loadTask("auth")?.stage)
            }
        }
    }

    @Test
    fun `when reply signals an illegal jump - then the stage is unchanged`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                // given
                // clarification → done is not in the table; the proposal is dropped.
                val memStore = MemoryStore(root).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.CLARIFICATION))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fake = FakeLlmApi().apply { queueText("Skipping ahead.\n[[stage:done]]") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")

                // when
                SessionLoop(
                    newChat(prompt = "hi", session = "demo"),
                    fake, store,
                    promptSource = stdinSource("/exit\n"),
                    memory = memory,
                ).run()

                // then
                assertEquals(TaskStage.CLARIFICATION, memStore.loadTask("auth")?.stage)
            }
        }
    }

    @Test
    fun `when reply has no stage marker - then the stage is unchanged`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                // given
                val memStore = MemoryStore(root).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.PLANNING))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fake = FakeLlmApi().apply { queueText("Still working on the plan.") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")

                // when
                SessionLoop(
                    newChat(prompt = "hi", session = "demo"),
                    fake, store,
                    promptSource = stdinSource("/exit\n"),
                    memory = memory,
                ).run()

                // then
                assertEquals(TaskStage.PLANNING, memStore.loadTask("auth")?.stage)
            }
        }
    }

    @Test
    fun `when the task is paused - then a stage marker does not advance it`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                // given
                val memStore = MemoryStore(root).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.PLANNING, paused = true))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fake = FakeLlmApi().apply { queueText("[[stage:execution]]") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")

                // when
                SessionLoop(
                    newChat(prompt = "hi", session = "demo"),
                    fake, store,
                    promptSource = stdinSource("/exit\n"),
                    memory = memory,
                ).run()

                // then
                assertEquals(TaskStage.PLANNING, memStore.loadTask("auth")?.stage)
            }
        }
    }
    //endregion

    //region pause/resume + creation

    @Test
    fun `when -task-pause then -task-resume issued - then the paused flag toggles`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                // given
                val memStore = MemoryStore(root).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.EXECUTION))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")

                // when
                SessionLoop(
                    newChat(prompt = "hi", session = "demo"),
                    fake, store,
                    promptSource = stdinSource("/task-pause\n/task-resume\n/exit\n"),
                    memory = memory,
                ).run()

                // then
                assertEquals(false, memStore.loadTask("auth")?.paused)
            }
        }
    }

    @Test
    fun `when slash task creates a fresh task - then it starts at the initial stage`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                // given
                val memStore = MemoryStore(root)
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM)
                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")

                // when
                SessionLoop(
                    newChat(prompt = "hi", session = "demo"),
                    fake, store,
                    promptSource = stdinSource("/task fresh\n/exit\n"),
                    memory = memory,
                ).run()

                // then
                assertEquals(TaskStage.CLARIFICATION, memStore.loadTask("fresh")?.stage)
            }
        }
    }
    //endregion

    //region helpers

    private fun newChat(prompt: String, session: String?): CliArgs.Chat = CliArgs.Chat(
        prompt = prompt,
        maxTokens = null,
        stopSequences = null,
        endSequence = null,
        temperature = null,
        modelProvider = ModelProvider.Gemini(model = GeminiModel.Default, apiKey = "test-key"),
        session = session,
        feedFile = null,
        chunkChars = 2500,
        feedInstruction = "",
        byLine = false,
        strategy = ContextStrategyKind.FULL,
        keepLast = 6,
        summarizeEvery = 10,
        task = null,
        profile = null,
        memoryMode = null,
        stageAgents = emptyList(),
        judgeAgents = emptyList(),
    )

    private fun stdinSource(script: String): StdinPromptSource =
        StdinPromptSource(BufferedReader(StringReader(script)))

    private inline fun withTempMemoryRoot(block: (java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("project01-agent-taskstate-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
    //endregion
}

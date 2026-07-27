package ru.den.writes.code.agenticHub.cliJvm

import ru.den.writes.code.agenticHub.features.llm.di.llmTestModule
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.FakeLlmScript
import org.koin.core.parameter.parametersOf
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemModule
import ru.den.writes.code.agenticHub.platform.database.MessageDao
import ru.den.writes.code.agenticHub.platform.database.di.databaseTestModule
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.platform.database.TestDb
import ru.den.writes.code.agenticHub.features.memory.ContextStrategyKind
import ru.den.writes.code.agenticHub.cliJvm.agent.createStdinPromptSource
import ru.den.writes.code.agenticHub.cliJvm.agent.runSessionForTest
import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.features.lifecycle.command.SessionConfig
import ru.den.writes.code.agenticHub.features.memory.db.RoomHistoryStore
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.FileMemoryStore
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.gemini.GeminiModel
import ru.den.writes.code.agenticHub.features.memory.MemoryMode
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import java.io.BufferedReader
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Agent-level behaviour of the task state machine: stages auto-advance from a
 * `[[stage:<next>]]` marker in the model's reply (validated against the
 * transition table), pause holds the stage, and a new task starts at the
 * initial stage. Kept separate from [AgentMemoryTest] (profile / rules /
 * memory-mode) to stay under the test-per-file limit.
 */
class AgentTaskStateTest {

    private fun scriptedApi(script: FakeLlmScript): LlmApi =
        koinApplication { modules(llmTestModule) }.koin.get { parametersOf(script) }


    //region auto-advance from the model reply

    @Test
    fun `when reply signals a legal next stage - then the task auto-advances`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.CLARIFICATION))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fakeScript = FakeLlmScript().apply { queueText("Requirements confirmed.\n[[stage:planning]]") }
                val fake = scriptedApi(fakeScript)
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "hi", session = "demo"),
                    fake, store,
                    promptSource = createStdinPromptSource("/exit\n"),
                    memory = memory,
                )

                // then
                assertEquals(TaskStage.PLANNING, memStore.loadTask("auth")?.stage)
            }
        }
    }

    @Test
    fun `when reply signals an illegal jump - then the stage is unchanged`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given
                // clarification → done is not in the table; the proposal is dropped.
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.CLARIFICATION))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fakeScript = FakeLlmScript().apply { queueText("Skipping ahead.\n[[stage:done]]") }
                val fake = scriptedApi(fakeScript)
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "hi", session = "demo"),
                    fake, store,
                    promptSource = createStdinPromptSource("/exit\n"),
                    memory = memory,
                )

                // then
                assertEquals(TaskStage.CLARIFICATION, memStore.loadTask("auth")?.stage)
            }
        }
    }

    @Test
    fun `when a stage stalls in an assembled session - then the composition root has armed the nudge`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given
                // The nudge is a composition-root decision (buildSessionViewModel passes
                // stallHint = true), so it can only be proven through an assembled session.
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.VALIDATION))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fakeScript = FakeLlmScript().apply {
                    repeat(3) { queueText("Still checking.\n[[stage:validation]]") }
                }
                val fake = scriptedApi(fakeScript)
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "hi", session = "demo"),
                    fake, store,
                    promptSource = createStdinPromptSource("go\ngo\n/exit\n"),
                    memory = memory,
                )

                // then
                val nudged = fakeScript.calls[2].messages.any { "[fsm] stalled:" in it.text }
                assertEquals(true, nudged, "third turn should carry the stall nudge")
            }
        }
    }

    @Test
    fun `when reply has no stage marker - then the stage is unchanged`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.PLANNING))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fakeScript = FakeLlmScript().apply { queueText("Still working on the plan.") }
                val fake = scriptedApi(fakeScript)
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "hi", session = "demo"),
                    fake, store,
                    promptSource = createStdinPromptSource("/exit\n"),
                    memory = memory,
                )

                // then
                assertEquals(TaskStage.PLANNING, memStore.loadTask("auth")?.stage)
            }
        }
    }

    @Test
    fun `when the task is paused - then a stage marker does not advance it`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.PLANNING, paused = true))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fakeScript = FakeLlmScript().apply { queueText("[[stage:execution]]") }
                val fake = scriptedApi(fakeScript)
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "hi", session = "demo"),
                    fake, store,
                    promptSource = createStdinPromptSource("/exit\n"),
                    memory = memory,
                )

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
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.EXECUTION))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fakeScript = FakeLlmScript().apply { queueText("ok") }
                val fake = scriptedApi(fakeScript)
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "hi", session = "demo"),
                    fake, store,
                    promptSource = createStdinPromptSource("/task pause\n/task resume\n/exit\n"),
                    memory = memory,
                )

                // then
                assertEquals(false, memStore.loadTask("auth")?.paused)
            }
        }
    }

    @Test
    fun `when slash task creates a fresh task - then it starts at the initial stage`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>())
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM)
                val fakeScript = FakeLlmScript().apply { queueText("ok") }
                val fake = scriptedApi(fakeScript)
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "hi", session = "demo"),
                    fake, store,
                    promptSource = createStdinPromptSource("/task fresh\n/exit\n"),
                    memory = memory,
                )

                // then
                assertEquals(TaskStage.CLARIFICATION, memStore.loadTask("fresh")?.stage)
            }
        }
    }
    //endregion

    //region a finished task leaves the session

    @Test
    fun `when the task reaches done - then it stops being the session's active task`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.VALIDATION))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fake = scriptedApi(FakeLlmScript().apply { queueText("All checks pass.\n[[stage:done]]") })
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "hi", session = "demo"),
                    fake, store,
                    promptSource = createStdinPromptSource("/exit\n"),
                    memory = memory,
                )

                // then — the task is finished on disk and no longer the one the session is running
                assertEquals(TaskStage.DONE, memStore.loadTask("auth")?.stage)
                assertNull(memory.activeTaskId())
            }
        }
    }

    @Test
    fun `when a session starts on a finished task - then it is not picked up as active`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given — a task left at done by an earlier session
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.DONE))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fake = scriptedApi(FakeLlmScript().apply { queueText("hello") })
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "hi", session = "demo"),
                    fake, store,
                    promptSource = createStdinPromptSource("/exit\n"),
                    memory = memory,
                )

                // then — dropped at hydrate, before the opening turn could be dressed as task work
                assertNull(memory.activeTaskId())
                assertEquals(TaskStage.DONE, memStore.loadTask("auth")?.stage)
            }
        }
    }
    //endregion

    //region helpers

    private fun newChat(prompt: String, session: String?): StartCommand.RunChat = StartCommand.RunChat(
        prompt = prompt,
        maxTokens = null,
        stopSequences = null,
        endSequence = null,
        temperature = null,
        modelProvider = ModelProvider.Gemini(model = GeminiModel.Default, apiKey = "test-key"),
        config = SessionConfig(
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
            tui = false,
            judgeAgents = emptyList(),
        ),
    )


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

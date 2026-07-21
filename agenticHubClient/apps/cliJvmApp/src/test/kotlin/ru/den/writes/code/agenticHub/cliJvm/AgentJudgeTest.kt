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
import ru.den.writes.code.agenticHub.features.agent.RoutedJudge
import ru.den.writes.code.agenticHub.features.memory.ContextStrategyKind
import ru.den.writes.code.agenticHub.cliJvm.agent.createStdinPromptSource
import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.cliJvm.agent.runSessionForTest
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.features.lifecycle.command.SessionConfig
import ru.den.writes.code.agenticHub.features.memory.db.RoomHistoryStore
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.FileMemoryStore
import ru.den.writes.code.agenticHub.features.agent.invariant.InvariantChecker
import ru.den.writes.code.agenticHub.features.agent.invariant.InvariantVerdict
import ru.den.writes.code.agenticHub.features.agent.invariant.InvariantViolation
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.llm.gemini.GeminiModel
import ru.den.writes.code.agenticHub.features.memory.MemoryMode
import ru.den.writes.code.agenticHub.features.memory.TaskBinding
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import java.io.BufferedReader
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Agent-level behaviour of the per-stage invariant judge: a flagged breach
 * suppresses the turn — the reply is shown but not persisted, and the stage is
 * held. A clean verdict (or a stage no judge spans) leaves the turn untouched.
 */
class AgentJudgeTest {

    private fun scriptedApi(script: FakeLlmScript): LlmApi =
        koinApplication { modules(llmTestModule) }.koin.get { parametersOf(script) }


    @Test
    fun `when judge flags a violation - then reply is not persisted and stage held`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given — task at clarification; the model would advance, but the judge objects
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.CLARIFICATION))
                    addRule("Kotlin only, no Spring")
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fakeScript = FakeLlmScript().apply { queueText("Use Spring Boot.\n[[stage:planning]]") }
                val fake = scriptedApi(fakeScript)
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "build auth", session = "demo"),
                    fake, store,
                    promptSource = createStdinPromptSource("/exit\n"),
                    memory = memory,
                    routedJudges = listOf(violatingJudge),
                )

                // then — stage held, turn dropped from history
                assertEquals(TaskStage.CLARIFICATION, memStore.loadTask("auth")?.stage)
                assertTrue(store.messages.isEmpty(), "violating turn must not be persisted")
            }
        }
    }

    @Test
    fun `when judge passes - then reply persists and stage advances`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.CLARIFICATION))
                    addRule("Kotlin only, no Spring")
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fakeScript = FakeLlmScript().apply { queueText("Confirmed, Kotlin it is.\n[[stage:planning]]") }
                val fake = scriptedApi(fakeScript)
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "build auth", session = "demo"),
                    fake, store,
                    promptSource = createStdinPromptSource("/exit\n"),
                    memory = memory,
                    routedJudges = listOf(cleanJudge),
                )

                // then — clean verdict leaves the turn untouched
                assertEquals(TaskStage.PLANNING, memStore.loadTask("auth")?.stage)
                assertEquals(2, store.messages.size)
            }
        }
    }

    @Test
    fun `when no judge spans the active stage - then the judge is not invoked`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given — task at execution, but the judge only covers clarification..planning
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.EXECUTION))
                    addRule("Kotlin only, no Spring")
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fakeScript = FakeLlmScript().apply { queueText("Working.\n[[stage:validation]]") }
                val fake = scriptedApi(fakeScript)
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "demo")
                var calls = 0
                val narrowJudge = RoutedJudge(
                    TaskBinding(TaskStage.CLARIFICATION, TaskStage.PLANNING),
                    InvariantChecker {
                        calls++
                        InvariantVerdict(passed = false, violations = listOf(InvariantViolation("001", "x")))
                    },
                    modelId = "test-judge",
                )

                // when
                runSessionForTest(
                    newChat(prompt = "go", session = "demo"),
                    fake, store,
                    promptSource = createStdinPromptSource("/exit\n"),
                    memory = memory,
                    routedJudges = listOf(narrowJudge),
                )

                // then — stage uncovered → no judge call, turn proceeds normally
                assertEquals(0, calls)
                assertEquals(TaskStage.VALIDATION, memStore.loadTask("auth")?.stage)
                assertEquals(2, store.messages.size)
            }
        }
    }

    @Test
    fun `when the first reply is flagged and the rewrite passes - then the rewrite persists`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given — the judge refuses the first answer and accepts the second
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.CLARIFICATION))
                    addRule("Kotlin only, no Spring")
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fakeScript = FakeLlmScript().apply {
                    queueText("Use Spring Boot.\n[[stage:planning]]")
                    queueText("Use Ktor instead.\n[[stage:planning]]")
                }
                val fake = scriptedApi(fakeScript)
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "build auth", session = "demo"),
                    fake, store,
                    promptSource = createStdinPromptSource("/exit\n"),
                    memory = memory,
                    routedJudges = listOf(judgeRefusingOnce()),
                )

                // then — the rewrite is what survives, and the stage moves on it
                assertEquals(2, fakeScript.calls.size, "the agent must be asked twice")
                assertEquals(TaskStage.PLANNING, memStore.loadTask("auth")?.stage)
                assertEquals(2, store.messages.size, "one exchange persists, not two")
                assertTrue(store.messages.last().text.contains("Ktor"), "the rewrite must be the stored reply")
            }
        }
    }

    @Test
    fun `when the agent is asked to rewrite - then it sees its rejected reply and the objections`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.CLARIFICATION))
                    addRule("Kotlin only, no Spring")
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fakeScript = FakeLlmScript().apply {
                    queueText("Use Spring Boot.\n[[stage:planning]]")
                    queueText("Use Ktor instead.")
                }
                val fake = scriptedApi(fakeScript)
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "build auth", session = "demo"),
                    fake, store,
                    promptSource = createStdinPromptSource("/exit\n"),
                    memory = memory,
                    routedJudges = listOf(judgeRefusingOnce()),
                )

                // then — the retry wire carries the rejected text and the critique, and the
                // stage marker is stripped so the agent doesn't think it already advanced
                val retryWire = fakeScript.calls[1].messages
                val quotedReply = retryWire.single { it.role == Role.ASSISTANT && it.text.contains("Use Spring Boot.") }
                assertTrue(
                    !quotedReply.text.contains("[[stage:"),
                    "the marker must be stripped, or the agent reads a stage it never reached",
                )
                val critique = retryWire.last()
                assertEquals(Role.USER, critique.role, "the critique must not be a SYSTEM turn")
                assertTrue(critique.text.contains("proposes Spring"), "the objection must reach the agent")
                assertTrue(
                    critique.text.contains("ALREADY run"),
                    "a rewrite must not re-run tools — a ticket opened by the rejected attempt already exists",
                )
            }
        }
    }

    @Test
    fun `when both attempts are flagged - then nothing persists and the stage is held`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given — an unappeasable judge
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.CLARIFICATION))
                    addRule("Kotlin only, no Spring")
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fakeScript = FakeLlmScript().apply {
                    queueText("Use Spring Boot.\n[[stage:planning]]")
                    queueText("Still Spring, sorry.")
                }
                val fake = scriptedApi(fakeScript)
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "build auth", session = "demo"),
                    fake, store,
                    promptSource = createStdinPromptSource("/exit\n"),
                    memory = memory,
                    routedJudges = listOf(violatingJudge),
                )

                // then — two attempts, then the pre-retry behaviour: dropped and held
                assertEquals(2, fakeScript.calls.size)
                assertEquals(TaskStage.CLARIFICATION, memStore.loadTask("auth")?.stage)
                assertTrue(store.messages.isEmpty(), "a twice-flagged turn must not be persisted")
            }
        }
    }

    @Test
    fun `when the rewrite call fails - then the turn degrades to blocked instead of erroring`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given — only one scripted answer, so the retry hits an empty queue
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.CLARIFICATION))
                    addRule("Kotlin only, no Spring")
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fakeScript = FakeLlmScript().apply { queueText("Use Spring Boot.") }
                val fake = scriptedApi(fakeScript)
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "build auth", session = "demo"),
                    fake, store,
                    promptSource = createStdinPromptSource("/exit\n"),
                    memory = memory,
                    routedJudges = listOf(violatingJudge),
                )

                // then — a wire failure on the retry must not cost more than the breach already did
                assertEquals(TaskStage.CLARIFICATION, memStore.loadTask("auth")?.stage)
                assertTrue(store.messages.isEmpty())
            }
        }
    }

    //region helpers

    /** Refuses the first reply it sees, waves through everything after. */
    private fun judgeRefusingOnce(): RoutedJudge {
        var seen = 0
        return RoutedJudge(
            TaskBinding(TaskStage.CLARIFICATION, TaskStage.DONE),
            InvariantChecker {
                if (seen++ == 0) {
                    InvariantVerdict(passed = false, violations = listOf(InvariantViolation("001", "proposes Spring")))
                } else {
                    InvariantVerdict.CLEAN
                }
            },
            modelId = "test-judge",
        )
    }

    private val violatingJudge = RoutedJudge(
        TaskBinding(TaskStage.CLARIFICATION, TaskStage.DONE),
        InvariantChecker {
            InvariantVerdict(passed = false, violations = listOf(InvariantViolation("001", "proposes Spring")))
        },
        modelId = "test-judge",
    )

    private val cleanJudge = RoutedJudge(
        TaskBinding(TaskStage.CLARIFICATION, TaskStage.DONE),
        InvariantChecker { InvariantVerdict.CLEAN },
        modelId = "test-judge",
    )

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
        val dir = Files.createTempDirectory("project01-agent-judge-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
    //endregion
}

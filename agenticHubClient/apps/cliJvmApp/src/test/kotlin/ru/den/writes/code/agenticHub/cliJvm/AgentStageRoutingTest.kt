package ru.den.writes.code.agenticHub.cliJvm

import ru.den.writes.code.agenticHub.testing.FakeLlmApi
import ru.den.writes.code.agenticHub.testing.TestDb
import ru.den.writes.code.agenticHub.features.agent.RoutedAgent
import ru.den.writes.code.agenticHub.features.memory.ContextStrategyKind
import ru.den.writes.code.agenticHub.cliJvm.agent.createStdinPromptSource

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.cliJvm.agent.runSessionForTest
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.features.lifecycle.command.SessionConfig
import ru.den.writes.code.agenticHub.features.memory.db.RoomHistoryStore
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.FileMemoryStore
import ru.den.writes.code.agenticHub.features.agent.AgentConfig
import ru.den.writes.code.agenticHub.features.agent.AgentResponder
import ru.den.writes.code.agenticHub.features.llm.GenerationParams
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.gemini.GeminiModel
import ru.den.writes.code.agenticHub.features.memory.MemoryMode
import ru.den.writes.code.agenticHub.features.memory.ProfileSection
import ru.den.writes.code.agenticHub.features.memory.TaskBinding
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Per-stage agent routing (via [TurnEngine]): a turn goes to the agent whose
 * [TaskBinding] covers the active task stage, otherwise the fallback. With no
 * routed agents the fallback handles every turn — single-agent parity.
 */
class AgentStageRoutingTest {

    @Test
    fun `when no routed agents - then the fallback handles every turn`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                // given
                val memStore = FileMemoryStore(root.absolutePath).apply {
                    saveTask(TaskNotes("t", stage = TaskStage.PLANNING))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "t")
                val fallback = FakeLlmApi().apply { queueText("ok") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "hi", session = "demo"),
                    fallback, store,
                    promptSource = createStdinPromptSource("/exit\n"),
                    memory = memory,
                )

                // then
                assertEquals(1, fallback.calls.size)
            }
        }
    }

    @Test
    fun `when the active stage matches a routed agent - then that agent answers`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                // given
                val memStore = FileMemoryStore(root.absolutePath).apply {
                    saveTask(TaskNotes("t", stage = TaskStage.PLANNING))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "t")
                val fallback = FakeLlmApi().apply { queueText("fallback") }
                val planner = FakeLlmApi().apply { queueText("planner") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "hi", session = "demo"),
                    fallback, store,
                    promptSource = createStdinPromptSource("/exit\n"),
                    memory = memory,
                    routedAgents = listOf(routed(TaskStage.PLANNING, TaskStage.EXECUTION, planner)),
                )

                // then
                assertEquals(1, planner.calls.size, "routed agent should answer")
                assertEquals(0, fallback.calls.size, "fallback should be idle")
            }
        }
    }

    @Test
    fun `when the active stage is outside every binding - then the fallback answers`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                // given
                val memStore = FileMemoryStore(root.absolutePath).apply {
                    saveTask(TaskNotes("t", stage = TaskStage.CLARIFICATION))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "t")
                val fallback = FakeLlmApi().apply { queueText("fallback") }
                val later = FakeLlmApi().apply { queueText("later") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "hi", session = "demo"),
                    fallback, store,
                    promptSource = createStdinPromptSource("/exit\n"),
                    memory = memory,
                    routedAgents = listOf(routed(TaskStage.EXECUTION, TaskStage.VALIDATION, later)),
                )

                // then
                assertEquals(1, fallback.calls.size, "an uncovered stage falls back")
                assertEquals(0, later.calls.size)
            }
        }
    }

    @Test
    fun `when a routed agent has a fixed profile - then that profile is on its wire`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                // given
                val memStore = FileMemoryStore(root.absolutePath).apply {
                    addNamedProfileItem("planner", ProfileSection.STYLE, "plan carefully")
                    saveTask(TaskNotes("t", stage = TaskStage.PLANNING))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "t")
                val fallback = FakeLlmApi().apply { queueText("fallback") }
                val planner = FakeLlmApi().apply { queueText("planner") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "hi", session = "demo"),
                    fallback, store,
                    promptSource = createStdinPromptSource("/exit\n"),
                    memory = memory,
                    routedAgents = listOf(
                        routed(TaskStage.PLANNING, TaskStage.EXECUTION, planner, profileName = "planner"),
                    ),
                )

                // then
                val wire = planner.calls.single().messages.joinToString("\n") { it.text }
                assertTrue(wire.contains("plan carefully"), "the routed agent's fixed profile should be injected")
            }
        }
    }

    @Test
    fun `when the reply advances the stage - then the next turn routes to the new stage's agent`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                // given
                val memStore = FileMemoryStore(root.absolutePath).apply {
                    saveTask(TaskNotes("t", stage = TaskStage.PLANNING))
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "t")
                val fallback = FakeLlmApi().apply { queueText("fallback") }
                // planner answers turn 1 and signals the legal move to execution.
                val planner = FakeLlmApi().apply { queueText("done planning [[stage:execution]]") }
                val executor = FakeLlmApi().apply { queueText("executing") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")

                // when — two user turns: the opening prompt, then one more line.
                runSessionForTest(
                    newChat(prompt = "hi", session = "demo"),
                    fallback, store,
                    promptSource = createStdinPromptSource("go on\n/exit\n"),
                    memory = memory,
                    routedAgents = listOf(
                        routed(TaskStage.PLANNING, TaskStage.PLANNING, planner),
                        routed(TaskStage.EXECUTION, TaskStage.VALIDATION, executor),
                    ),
                )

                // then
                assertEquals(1, planner.calls.size, "turn 1 (planning) routes to the planner")
                assertEquals(1, executor.calls.size, "turn 2 (execution) routes to the executor")
                assertEquals(TaskStage.EXECUTION, memStore.loadTask("t")?.stage)
            }
        }
    }

    //region agent tag

    @Test
    fun `when multi-agent - then the reply is prefixed with the agent tag`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                // given
                val memStore = FileMemoryStore(root.absolutePath).apply { saveTask(TaskNotes("t", stage = TaskStage.PLANNING)) }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "t")
                val fallback = FakeLlmApi().apply { queueText("fb") }
                val planner = FakeLlmApi().apply { queueText("the plan") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")

                // when
                val out = captureStdout {
                    runSessionForTest(
                        newChat(prompt = "hi", session = "demo"),
                        fallback, store,
                        promptSource = createStdinPromptSource("/exit\n"),
                        memory = memory,
                        routedAgents = listOf(
                            routed(
                                TaskStage.PLANNING, TaskStage.EXECUTION, planner,
                                profileName = "planner", modelId = "gemini-2.5-flash",
                            ),
                        ),
                    )
                }

                // then
                assertTrue(
                    out.contains("[[AGENT: planner:gemini-2.5-flash]]"),
                    "reply should carry the agent tag",
                )
            }
        }
    }

    @Test
    fun `when single-agent - then the reply has no agent tag`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                // given
                val memStore = FileMemoryStore(root.absolutePath).apply { saveTask(TaskNotes("t", stage = TaskStage.PLANNING)) }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "t")
                val fake = FakeLlmApi().apply { queueText("plain reply") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")

                // when — no routed agents
                val out = captureStdout {
                    runSessionForTest(
                        newChat(prompt = "hi", session = "demo"),
                        fake, store,
                        promptSource = createStdinPromptSource("/exit\n"),
                        memory = memory,
                    )
                }

                // then
                assertFalse(out.contains("[[AGENT:"), "single-agent output must not carry the tag")
            }
        }
    }
    //endregion

    //region helpers

    private fun routed(
        from: TaskStage,
        to: TaskStage,
        api: FakeLlmApi,
        profileName: String? = null,
        modelId: String = "routed-model",
    ): RoutedAgent = RoutedAgent(
        binding = TaskBinding(from, to),
        responder = AgentResponder(AgentConfig(llmApi = api, params = GenerationParams(), profileName = profileName)),
        profileName = profileName,
        modelId = modelId,
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
        val dir = Files.createTempDirectory("project01-agent-routing-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private inline fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buf = ByteArrayOutputStream()
        System.setOut(PrintStream(buf, true, "UTF-8"))
        try {
            block()
        } finally {
            System.out.flush()
            System.setOut(original)
        }
        return buf.toString("UTF-8")
    }
    //endregion
}

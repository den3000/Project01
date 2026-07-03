package ru.den.writes.code.agenticHub.cliJvm.agent

import ru.den.writes.code.agenticHub.features.llm.di.llmTestModule
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.FakeLlmScript
import org.koin.core.parameter.parametersOf
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemModule
import ru.den.writes.code.agenticHub.platform.database.MessageDao
import ru.den.writes.code.agenticHub.platform.database.di.databaseTestModule
import org.koin.dsl.koinApplication
import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.agent.RoutedAgent
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.StageAdvance
import ru.den.writes.code.agenticHub.platform.database.TestDb
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.TurnEngine
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.TurnResult
import ru.den.writes.code.agenticHub.features.memory.db.RoomHistoryStore
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.FileMemoryStore
import ru.den.writes.code.agenticHub.features.agent.AgentConfig
import ru.den.writes.code.agenticHub.features.agent.AgentResponder
import ru.den.writes.code.agenticHub.features.llm.GenerationParams
import ru.den.writes.code.agenticHub.features.llm.LlmResult
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.memory.MemoryMode
import ru.den.writes.code.agenticHub.features.memory.TaskBinding
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Offline tests for [TurnEngine] — the pure turn engine. No I/O is asserted
 * here (the engine doesn't print); coverage is over persistence, the
 * [TurnResult] it returns, and the task-stage FSM outcome.
 */
class TurnEngineTest {

    private fun scriptedApi(script: FakeLlmScript): LlmApi =
        koinApplication { modules(llmTestModule) }.koin.get { parametersOf(script) }


    @Test
    fun `when a turn succeeds - then both sides persist and Ok carries the snapshot`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            // given
            val fakeScript = FakeLlmScript().apply { queueText("reply", promptTokens = 12, outputTokens = 3) }
            val fake = scriptedApi(fakeScript)
            val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "s")
            val engine = TurnEngine(newChat("hi", "s"), fake, store)

            // when
            val result = engine.turn("hi")

            // then
            assertTrue(result is TurnResult.Ok)
            assertEquals("reply", result.reply)
            assertEquals(12, result.usage?.promptTokens)
            assertEquals(StageAdvance.None, result.stageAdvance)
            assertEquals(1, result.session?.turns)
            val reader = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "s").apply { load() }
            assertEquals(
                listOf(Message(Role.USER, "hi"), Message(Role.ASSISTANT, "reply")),
                reader.messages,
            )
        }
    }

    @Test
    fun `when the provider errors - then Failed and nothing persisted`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            // given
            val fakeScript = FakeLlmScript()
            val fake = scriptedApi(fakeScript) // empty queue → synthetic error
            val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "s")
            val engine = TurnEngine(newChat("hi", "s"), fake, store)

            // when
            val result = engine.turn("hi")

            // then
            assertEquals(TurnResult.Failed("FakeLlmScript: no scripted response"), result)
            assertEquals(0, koin.get<MessageDao>().count())
        }
    }

    @Test
    fun `when the reply is empty with no usage - then Failed with that reason`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            // given
            val fakeScript = FakeLlmScript().apply { queue(LlmResult(text = null)) }
            val fake = scriptedApi(fakeScript)
            val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "s")
            val engine = TurnEngine(newChat("hi", "s"), fake, store)

            // when
            val result = engine.turn("hi")

            // then
            assertEquals(TurnResult.Failed("empty response with no usage"), result)
        }
    }

    @Test
    fun `when the reply signals a legal stage move - then Advanced and the task is saved`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply { saveTask(TaskNotes("t", stage = TaskStage.PLANNING)) }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "t")
                val fakeScript = FakeLlmScript().apply { queueText("on it [[stage:execution]]") }
                val fake = scriptedApi(fakeScript)
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "s")
                val engine = TurnEngine(newChat("hi", "s"), fake, store, memory = memory)

                // when
                val result = engine.turn("hi")

                // then
                assertEquals(
                    StageAdvance.Advanced(TaskStage.PLANNING, TaskStage.EXECUTION),
                    (result as TurnResult.Ok).stageAdvance,
                )
                assertEquals(TaskStage.EXECUTION, memStore.loadTask("t")?.stage)
            }
        }
    }

    @Test
    fun `when the reply signals an illegal stage move - then Rejected and the task is unchanged`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given — DONE isn't reachable from PLANNING
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply { saveTask(TaskNotes("t", stage = TaskStage.PLANNING)) }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "t")
                val fakeScript = FakeLlmScript().apply { queueText("skip ahead [[stage:done]]") }
                val fake = scriptedApi(fakeScript)
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "s")
                val engine = TurnEngine(newChat("hi", "s"), fake, store, memory = memory)

                // when
                val result = engine.turn("hi")

                // then
                val advance = (result as TurnResult.Ok).stageAdvance
                assertTrue(advance is StageAdvance.Rejected)
                assertEquals(TaskStage.PLANNING, advance.from)
                assertEquals(TaskStage.DONE, advance.proposed)
                assertEquals(TaskStage.PLANNING, memStore.loadTask("t")?.stage)
            }
        }
    }

    @Test
    fun `when the active stage matches a routed agent - then that agent answers`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            withTempMemoryRoot { root ->
                // given
                val memStore = FileMemoryStore(root.absolutePath, fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).apply { saveTask(TaskNotes("t", stage = TaskStage.PLANNING)) }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "t")
                val fallbackScript = FakeLlmScript().apply { queueText("fb") }
                val fallback = scriptedApi(fallbackScript)
                val plannerScript = FakeLlmScript().apply { queueText("planned") }
                val planner = scriptedApi(plannerScript)
                val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "s")
                val engine = TurnEngine(
                    newChat("hi", "s"), fallback, store, memory = memory,
                    routedAgents = listOf(routed(TaskStage.PLANNING, TaskStage.EXECUTION, planner, "planner")),
                )

                // when
                val result = engine.turn("hi")

                // then
                result as TurnResult.Ok
                assertEquals("planned", result.reply)
                assertEquals("planner", result.profileName)
                assertEquals(1, plannerScript.calls.size)
                assertEquals(0, fallbackScript.calls.size)
            }
        }
    }

    //region helpers

    private fun routed(
        from: TaskStage,
        to: TaskStage,
        api: LlmApi,
        profileName: String? = null,
    ): RoutedAgent = RoutedAgent(
        binding = TaskBinding(from, to),
        responder = AgentResponder(AgentConfig(llmApi = api, params = GenerationParams(), profileName = profileName)),
        profileName = profileName,
        modelId = "routed-model",
    )

    private inline fun withTempMemoryRoot(block: (java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("project01-turn-engine-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
    //endregion
}

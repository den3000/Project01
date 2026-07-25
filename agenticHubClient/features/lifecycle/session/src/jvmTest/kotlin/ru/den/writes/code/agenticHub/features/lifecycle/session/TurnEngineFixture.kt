package ru.den.writes.code.agenticHub.features.lifecycle.session

import kotlinx.coroutines.test.TestScope
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.agent.RoutedAgent
import ru.den.writes.code.agenticHub.features.agent.RoutedJudge
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.TurnEngine
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.di.llmModule
import ru.den.writes.code.agenticHub.features.memory.FileMemoryStore
import ru.den.writes.code.agenticHub.features.memory.MemoryMode
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import ru.den.writes.code.agenticHub.features.memory.db.RoomHistoryStore
import ru.den.writes.code.agenticHub.platform.database.MessageDao
import ru.den.writes.code.agenticHub.platform.database.TestDb
import ru.den.writes.code.agenticHub.platform.database.di.databaseTestModule
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemModule
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import java.io.File
import java.nio.file.Files

/**
 * Assembling a real [TurnEngine] for a test, offline or live.
 *
 * One environment serves both: the engine, its memory and its history are wired the same
 * way in either case, and only the [LlmApi] differs — a scripted fake offline, the real
 * provider live. Keeping that seam as a factory (rather than two builders) is what stops
 * the offline suite and the live stand from drifting into testing different engines.
 *
 * The pieces the environment is built from — tasks, prompts, scripted fakes, stage agents —
 * live in `TurnEngineTestSupport.kt`; the reporting the live stand needs on top of a run —
 * per-turn logs, stall spans, the tables — in `TurnEngineRunReport.kt`.
 */

/**
 * The engine plus the state a test needs to assert against it: the task file it advances
 * and the history it persists to. Both are read back through their real stores rather than
 * captured in memory — a test that trusts its own bookkeeping cannot catch a persist that
 * silently did nothing.
 */
internal class TurnEngineFixture(
    val engine: TurnEngine,
    val memStore: FileMemoryStore,
    private val dao: MessageDao,
    private val sessionId: String,
) {
    /** Stage the task sits at right now, straight off disk. */
    fun stageOf(taskId: String): TaskStage? = memStore.loadTask(taskId)?.stage

    /** Rows in the message table — 0 proves a failed turn persisted nothing. */
    suspend fun persistedCount(): Int = dao.count()

    /** History as the next session would load it: both sides of every persisted turn. */
    suspend fun persistedMessages(): List<Message> =
        RoomHistoryStore(dao, sessionId = sessionId).apply { load() }.messages
}

/**
 * Build a [TurnEngine] over a throwaway environment (in-memory DB, temp memory root) and
 * hand it to [block]. Everything is torn down afterwards, including on failure.
 *
 * [llmApi] is a factory rather than a value because the live stand needs the graph to build
 * its client (`buildLlmApi(provider, koin.get())`) while offline tests just close over a
 * scripted fake — the same call either way.
 *
 * [task] is saved before the engine is built, so the first turn already sees a stage; pass
 * null for the task-less path. Profile items and later task edits can go through
 * [TurnEngineFixture.memStore] inside the block: the memory layer is re-read every turn.
 */
internal suspend fun <T> TestScope.withTurnEngine(
    llmApi: (Koin) -> LlmApi,
    task: TaskNotes? = null,
    stallHint: Boolean = false,
    routedAgents: List<RoutedAgent> = emptyList(),
    routedJudges: List<RoutedJudge> = emptyList(),
    modelProvider: ModelProvider = dummyProvider(),
    sessionName: String = "s",
    prompt: String = "hi",
    temperature: Double? = null,
    block: suspend TurnEngineFixture.() -> T,
): T = withSessionEnv { koin, fsRoot ->
    val memStore = FileMemoryStore(fsRoot.absolutePath, fs = koin.get<LocalFileSystem>())
    task?.let(memStore::saveTask)
    val dao = koin.get<MessageDao>()
    val engine = TurnEngine(
        newChat(prompt, sessionName, modelProvider, temperature),
        llmApi(koin),
        RoomHistoryStore(dao, sessionId = sessionName),
        memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = task?.taskId),
        routedAgents = routedAgents,
        routedJudges = routedJudges,
        stallHint = stallHint,
    )
    TurnEngineFixture(engine, memStore, dao, sessionName).block()
}

/** Koin graph + temp filesystem root + in-memory database, all disposed after [block]. */
private suspend fun <T> TestScope.withSessionEnv(block: suspend TestScope.(Koin, File) -> T): T {
    val fsRoot = Files.createTempDirectory("project01-turn-engine-").toFile()
    try {
        TestDb().use { appDb ->
            val koin = koinApplication {
                modules(llmModule, networkModule, fileSystemModule, databaseTestModule(appDb.db))
            }.koin
            return block(koin, fsRoot)
        }
    } finally {
        fsRoot.deleteRecursively()
    }
}

package ru.den.writes.code.agenticHub.features.lifecycle.session

import kotlinx.coroutines.test.TestScope
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.agent.RoutedAgent
import ru.den.writes.code.agenticHub.features.agent.RoutedJudge
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.FsmTurnEngine
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.InlineFsmTurnEngine
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
import ru.den.writes.code.agenticHub.features.memory.db.HistoryStore
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
 * Assembling a real [TurnEngine] for a test, offline or live, and running turns against it.
 *
 * One environment serves both: the engine, its memory and its history are wired the same
 * way in either case, and only the [LlmApi] differs — a scripted fake offline, the real
 * provider live. Keeping that seam as a factory (rather than two builders) is what stops
 * the offline suite and the live stand from drifting into testing different engines. The
 * same goes for [runTurnEngineWith]: one driver, two faces.
 *
 * The pieces the environment is built from — tasks, prompts, scripted fakes, stage agents —
 * live in `TurnEngineTestSupport.kt`; the reporting the live stand needs on top of a run —
 * the run records themselves, stall spans, the tables — in `TurnEngineRunReport.kt`.
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

    /** Branches this session has rows in, oldest first — a restart opens another one. */
    suspend fun branches(): List<String> = RoomHistoryStore(dao, sessionId = sessionId).branches()
}

/**
 * Everything an engine is built from, so a test can name WHICH engine it is driving without
 * knowing how to assemble one.
 */
internal data class EngineDeps(
    val cliArgs: StartCommand.SessionInitialState,
    val llmApi: LlmApi,
    val historyStore: HistoryStore,
    val memory: MemoryProvider,
    val routedAgents: List<RoutedAgent>,
    val routedJudges: List<RoutedJudge>,
    val stallHint: Boolean,
)

/**
 * An engine under test, with a name for the assert message.
 *
 * The seam the conformance suite runs on: one test, both engines, and a failure that says
 * which of them broke. [name] is short because it is printed on every assertion.
 */
internal data class EngineUnderTest(val name: String, val create: (EngineDeps) -> TurnEngine) {
    override fun toString(): String = name
}

/** The engine whose FSM lives inside it (`features:memory` table, private counters). */
internal val INLINE_ENGINE: EngineUnderTest = EngineUnderTest("inline") { d ->
    InlineFsmTurnEngine(
        d.cliArgs,
        d.llmApi,
        d.historyStore,
        memory = d.memory,
        routedAgents = d.routedAgents,
        routedJudges = d.routedJudges,
        stallHint = d.stallHint,
    )
}

/**
 * The engine that delegates to `features:fsm`. [EngineDeps.stallHint] is ignored on purpose:
 * this one arms its nudge off the stage budget, so there is no flag to turn on.
 */
internal val FSM_ENGINE: EngineUnderTest = EngineUnderTest("fsm") { d ->
    FsmTurnEngine(
        d.cliArgs,
        d.llmApi,
        d.historyStore,
        memory = d.memory,
        routedAgents = d.routedAgents,
        routedJudges = d.routedJudges,
    )
}

/** Both engines, for a test that must hold for either. */
internal val BOTH_ENGINES: List<EngineUnderTest> = listOf(INLINE_ENGINE, FSM_ENGINE)

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
    engineUnderTest: EngineUnderTest = INLINE_ENGINE,
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
    val engine = engineUnderTest.create(
        EngineDeps(
            cliArgs = newChat(prompt, sessionName, modelProvider, temperature),
            llmApi = llmApi(koin),
            historyStore = RoomHistoryStore(dao, sessionId = sessionName),
            memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = task?.taskId),
            routedAgents = routedAgents,
            routedJudges = routedJudges,
            stallHint = stallHint,
        ),
    )
    TurnEngineFixture(engine, memStore, dao, sessionName).block()
}

/**
 * Build the engine over a throwaway environment, feed it [turns] turns, and return the run.
 *
 * The one entry point both suites drive the engine through: offline a test hands it a
 * scripted [LlmApi] and a turn count, the live stand a real provider and [MAX_TURNS] (see
 * the overload in `TurnEngineRunReport.kt`). Everything a turn produces is on the returned
 * [RunLog] — a test asserts on it, the stand tabulates it — so neither suite reaches for
 * `engine.turn` itself and they cannot drift apart.
 *
 * [prompt] is the first turn's, [followUpPrompt] every later one's — the "continue" a
 * headless run pipes in. By default the loop stops early once the task reaches DONE: a
 * finished task has nothing left to answer, and the stand measures how many turns that took.
 * [stopAtDone] = false keeps feeding it anyway — a real session does exactly that, and it is
 * the only way to reach the engine's behaviour AT the terminal stage.
 *
 * [profileItems] are planted before the first turn; the memory layer is re-read every turn,
 * so they are live for the whole run.
 *
 * [logTurns] prints each turn as it lands. Only the stand wants it (a run is minutes long
 * and the output is watched live); offline it would be noise.
 */
internal suspend fun TestScope.runTurnEngineWith(
    llmApi: (Koin) -> LlmApi,
    engineUnderTest: EngineUnderTest = INLINE_ENGINE,
    task: TaskNotes? = null,
    turns: Int = 1,
    prompt: String = OPENING_PROMPT,
    followUpPrompt: String = FOLLOW_UP_PROMPT,
    stallHint: Boolean = false,
    routedAgents: List<RoutedAgent> = emptyList(),
    routedJudges: List<RoutedJudge> = emptyList(),
    profileItems: List<ProfileItem> = emptyList(),
    modelProvider: ModelProvider = dummyProvider(),
    sessionName: String = "s",
    temperature: Double? = null,
    stopAtDone: Boolean = true,
    logTurns: Boolean = false,
): RunLog = withTurnEngine(
    llmApi = llmApi,
    engineUnderTest = engineUnderTest,
    task = task,
    stallHint = stallHint,
    routedAgents = routedAgents,
    routedJudges = routedJudges,
    modelProvider = modelProvider,
    sessionName = sessionName,
    prompt = prompt,
    temperature = temperature,
) {
    profileItems.forEach { memStore.addNamedProfileItem(it.agent, it.section, it.text) }
    // The turns run first: everything below is what they left behind, not what they started from.
    val turnLogs = runTurns(task, turns, prompt, followUpPrompt, stopAtDone, logTurns)
    RunLog(
        modelId = modelProvider.modelId,
        sessionName = sessionName,
        taskId = task?.taskId,
        finalStage = task?.let { stageOf(it.taskId) },
        turnLogs = turnLogs,
        persistedMessages = persistedMessages(),
        persistedCount = persistedCount(),
    )
}

/** Feed the engine up to [turns] turns, logging each; stops at DONE unless told otherwise. */
private suspend fun TurnEngineFixture.runTurns(
    task: TaskNotes?,
    turns: Int,
    prompt: String,
    followUpPrompt: String,
    stopAtDone: Boolean,
    logTurns: Boolean,
): List<TurnLog> = (0..<turns).mapNotNull { index ->
    val stageBefore = task?.let { stageOf(it.taskId) }
    if (stopAtDone && stageBefore == TaskStage.DONE) return@mapNotNull null

    val turnPrompt = if (index == 0) prompt else followUpPrompt
    TurnLog(index, stageBefore, turnPrompt, engine.turn(turnPrompt))
        .also { if (logTurns) println(it.formatted()) }
}

/** Koin graph + temp filesystem root + in-memory database, all disposed after [block]. */
internal suspend fun <T> TestScope.withSessionEnv(block: suspend TestScope.(Koin, File) -> T): T {
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

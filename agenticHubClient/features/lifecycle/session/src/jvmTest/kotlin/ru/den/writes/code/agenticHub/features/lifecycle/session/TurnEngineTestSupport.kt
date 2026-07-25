package ru.den.writes.code.agenticHub.features.lifecycle.session

import kotlinx.coroutines.test.TestScope
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.agent.AgentConfig
import ru.den.writes.code.agenticHub.features.agent.AgentResponder
import ru.den.writes.code.agenticHub.features.agent.RoutedAgent
import ru.den.writes.code.agenticHub.features.agent.RoutedJudge
import ru.den.writes.code.agenticHub.features.agent.invariant.InvariantChecker
import ru.den.writes.code.agenticHub.features.agent.invariant.InvariantVerdict
import ru.den.writes.code.agenticHub.features.agent.invariant.JudgeInput
import ru.den.writes.code.agenticHub.features.lifecycle.command.SessionConfig
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.TurnEngine
import ru.den.writes.code.agenticHub.features.llm.FakeLlmScript
import ru.den.writes.code.agenticHub.features.llm.GenerationParams
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.di.llmModule
import ru.den.writes.code.agenticHub.features.llm.di.llmTestModule
import ru.den.writes.code.agenticHub.features.llm.gemini.GeminiModel
import ru.den.writes.code.agenticHub.features.memory.ContextStrategyKind
import ru.den.writes.code.agenticHub.features.memory.FileMemoryStore
import ru.den.writes.code.agenticHub.features.memory.MemoryMode
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.TaskBinding
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
 * The reporting machinery the live stand needs on top of this — per-turn logs, stall
 * spans, the tables — lives in `TurnEngineRunReport.kt`; offline tests never touch it.
 */

//region фикстуры прогона

/**
 * NO_MOVE turns in a row that make a stall episode. Mirrors the engine's own
 * `STALL_STREAK_LIMIT` (TurnEngine.kt) — the point where the stall nudge arms —
 * so `stalled`/`recovered` describe exactly the runs the nudge could act on.
 * The engine's constant is file-private, so this copy is kept in sync by hand.
 */
internal const val STALL_STREAK_LIMIT = 2

internal const val OPENING_PROMPT =
    "Begin the task. I have no requirements beyond the goal — decide the details yourself " +
        "and move the task forward without asking me questions."

internal const val FOLLOW_UP_PROMPT = "continue"

internal const val MAX_TURNS = 10

internal val SIMPLE_TASK = TaskNotes(
    taskId = "simple-task",
    goal = "Compose a three-item pre-release checklist for a small command-line tool. " +
        "Each item is one sentence. The checklist text itself is the whole deliverable — " +
        "no files, no tools, no external systems.",
    stage = TaskStage.CLARIFICATION,
)

/**
 * The opposite lever from a "hard" task: the deliverable is a SINGLE sentence, ready the
 * moment planning ends, so EXECUTION has nothing left to do. On each `continue` the model
 * tends to repeat ("I already said it") and degenerate into the execution-lock (NO_MOVE)
 * instead of signalling validation — the failure mode a stall hint targets. Short replies
 * also keep runs fast and within the live budget, unlike the volume a "cover as many
 * scenarios as you can" task provokes (which collapsed into 503s and a 15-min timeout).
 * Pure text — no tools, RAG or judge — to isolate the FSM marker channel.
 */
internal val MINIMAL_TASK = TaskNotes(
    taskId = "minimal-task",
    goal = "Name the single most important pre-release check for a small command-line " +
        "tool, in one short sentence. That one sentence is the entire deliverable — " +
        "nothing else: no explanation, no list, no extra text.",
    stage = TaskStage.CLARIFICATION,
)

//endregion

//region окружение движка

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

//endregion

//region фейки и агенты

/** [LlmApi] backed by [script] — the offline stand-in for a provider. */
internal fun scriptedApi(script: FakeLlmScript): LlmApi =
    koinApplication { modules(llmTestModule) }.koin.get { parametersOf(script) }

/** A stage agent spanning [from]..[to], answering through [api]. */
internal fun routedAgent(
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

/**
 * A judge that passes every turn and keeps what it was handed. Verdict-only assertions do
 * not need it, but "what does the judge actually see" does — and that question has cost
 * live runs before (the profile `context` section must never reach it).
 */
internal class RecordingJudge(private val verdict: InvariantVerdict = InvariantVerdict.CLEAN) {
    var seen: JudgeInput? = null
        private set

    fun routed(span: TaskBinding = TaskBinding(TaskStage.CLARIFICATION, TaskStage.DONE)): RoutedJudge =
        RoutedJudge(span, InvariantChecker { seen = it; verdict }, modelId = "judge-model")
}

//endregion

//region команда запуска

/**
 * Provider the offline path passes to the engine: the concrete [LlmApi] is already a fake,
 * but `RunChat` insists on a non-null [ModelProvider], and its model id shows up in results.
 */
internal fun dummyProvider(model: GeminiModel = GeminiModel.Default): ModelProvider =
    ModelProvider.Gemini(model = model, apiKey = "test-key")

/**
 * The session command the engine reads its generation knobs from. Only [temperature] is
 * exposed: it is the one knob `RunChat` actually carries into `GenerationParams`. (A
 * `thinkingBudget` argument used to sit here and was silently dropped — `RunChat` has no
 * such field, so the stand ran with thinking ON while its call site read as if off.)
 */
private fun newChat(
    prompt: String,
    session: String?,
    modelProvider: ModelProvider,
    temperature: Double? = null,
) = StartCommand.RunChat(
    prompt = prompt,
    maxTokens = null,
    stopSequences = null,
    endSequence = null,
    temperature = temperature,
    modelProvider = modelProvider,
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

//endregion

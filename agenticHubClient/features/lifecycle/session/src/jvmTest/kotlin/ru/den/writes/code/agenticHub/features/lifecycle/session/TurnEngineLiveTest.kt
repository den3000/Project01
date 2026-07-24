package ru.den.writes.code.agenticHub.features.lifecycle.session

import kotlinx.coroutines.test.TestScope
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.BuildKonfig
import ru.den.writes.code.agenticHub.features.lifecycle.command.SessionConfig
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.StageAdvance
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.TurnEngine
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.TurnResult
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.buildLlmApi
import ru.den.writes.code.agenticHub.features.llm.di.llmModule
import ru.den.writes.code.agenticHub.features.llm.gemini.GeminiModel
import ru.den.writes.code.agenticHub.features.memory.ContextStrategyKind
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
import ru.den.writes.code.agenticHub.testutils.runLiveTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnEngineLiveTest {

    @Test
    fun `when turn engine works without task - then it is pure hallucination`() = runLiveTest {
        // given
        val modelProvider = ModelProvider.Gemini(
            model = GeminiModel.Default,
            apiKey = BuildKonfig.GEMINI_API_KEY
        )
        val sessionName = "turn-engine-live-test"

        // when
        val runLog = runTurnEngineWith(modelProvider, sessionName, null)

        // then
        assertEquals(MAX_TURNS, runLog.turnLogs.size)
    }

    @Test
    fun `when turn engine works with simple task - then it finishes faster then max test tuens`() = runLiveTest {
        // given
        val modelProvider = ModelProvider.Gemini(
            model = GeminiModel.Default,
            apiKey = BuildKonfig.GEMINI_API_KEY
        )
        val sessionName = "turn-engine-live-test"

        // when
        val runLog = runTurnEngineWith(modelProvider, sessionName, SIMPLE_TASK)

        // then
        assertTrue { runLog.turnLogs.size < MAX_TURNS }
    }

    @Test
    fun `when turn engine uses different models with simple task - then works any time`() = runLiveTest {
        // given
        val modelProvider1 = ModelProvider.Gemini(
            model = GeminiModel.Known.Gemini25FlashLite,
            apiKey = BuildKonfig.GEMINI_API_KEY
        )
        val modelProvider2 = ModelProvider.Gemini(
            model = GeminiModel.Known.Gemini31FlashLite,
            apiKey = BuildKonfig.GEMINI_API_KEY
        )
        val modelProvider3 = ModelProvider.Gemini(
            model = GeminiModel.Known.Gemini25Flash,
            apiKey = BuildKonfig.GEMINI_API_KEY
        )
        val sessionName = "turn-engine-live-test"

        // when
        val tries = 3
        val runLogs1 = (0..<tries).mapNotNull { runTurnEngineWith(modelProvider1, sessionName, SIMPLE_TASK) }
        val runLogs2 = (0..<tries).mapNotNull { runTurnEngineWith(modelProvider2, sessionName, SIMPLE_TASK) }
        val runLogs3 = (0..<tries).mapNotNull { runTurnEngineWith(modelProvider3, sessionName, SIMPLE_TASK) }

        // then — diagnostic summary per model across tries (no hard assert yet)
        listOf(runLogs1, runLogs2, runLogs3).forEach { runs -> printModelSummary(runs, tries) }
    }

    private fun printModelSummary(runs: List<RunLog>, tries: Int) {
        val model = runs.firstOrNull()?.modelId ?: "?"
        val reached = runs.count { it.reachedDone }
        val turns = runs.map { it.turnLogs.size }
        println("========================= MODEL SUMMARY =========================")
        println("MODEL $model — reachedDone $reached/$tries — turns per try $turns")
        runs.forEach { println(it.formatted()) }
    }


    private suspend fun TestScope.runTurnEngineWith(modelProvider: ModelProvider, sessionName: String, taskNotes: TaskNotes?) =
        withKoinAndTmpFsRoot { koin, tempFsRoot ->
            val memStore = FileMemoryStore(tempFsRoot.absolutePath, fs = koin.get<LocalFileSystem>())
            val memory = if (taskNotes != null) {
                memStore.saveTask(taskNotes)
                MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = taskNotes.taskId)
            } else {
                MemoryProvider(memStore, MemoryMode.SYSTEM)
            }
            val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = sessionName)
            val api = buildLlmApi(modelProvider, koin.get())
            val chatCmd = newChat(
                prompt = OPENING_PROMPT,
                session = sessionName,
                modelProvider = modelProvider,
                temperature = null,
                thinkingBudget = 0
            )
            val engine = TurnEngine(chatCmd, api, store, memory = memory)
            val turns = runTurnsWith(engine, memStore, taskNotes)
            val finalStage = taskNotes?.let { memStore.loadTask(it.taskId)?.stage }
            return@withKoinAndTmpFsRoot RunLog(
                modelId = modelProvider.modelId,
                sessionName = sessionName,
                taskId = taskNotes?.taskId,
                finalStage = finalStage,
                turnLogs = turns
            )
        }

    private suspend fun runTurnsWith(
        engine: TurnEngine,
        memStore: FileMemoryStore,
        taskNotes: TaskNotes?
    ) =
        (0..<MAX_TURNS).mapNotNull { index: Int ->
            val stageBefore = taskNotes?.let { memStore.loadTask(it.taskId)?.stage }
            if (stageBefore == TaskStage.DONE) {
                return@mapNotNull null
            }

            val prompt = if (index == 0) OPENING_PROMPT else FOLLOW_UP_PROMPT
            val turnLog = when (val result = engine.turn(prompt)) {
                is TurnResult.Failed -> {
                    TurnLog(
                        index = index,
                        stageBefore = stageBefore,
                        prompt = prompt,
                        reply = "",
                        suggestedNextStage = null,
                        replayErrorReason = result.reason,
                    )
                }

                is TurnResult.Ok -> {
                    when (val advance = result.stageAdvance) {
                        StageAdvance.None -> {
                            TurnLog(
                                index = index,
                                stageBefore = stageBefore,
                                prompt = prompt,
                                reply = result.reply,
                                suggestedNextStage = null,
                                judge = result.judge::class.simpleName.orEmpty(),
                                outputTokens = result.usage?.outputTokens,
                                thoughtsTokens = result.usage?.thoughtsTokens,
                                durationMs = result.durationMs,
                            )
                        }

                        is StageAdvance.Advanced -> {
                            TurnLog(
                                index = index,
                                stageBefore = stageBefore,
                                prompt = prompt,
                                reply = result.reply,
                                suggestedNextStage = advance.to,
                                judge = result.judge::class.simpleName.orEmpty(),
                                outputTokens = result.usage?.outputTokens,
                                thoughtsTokens = result.usage?.thoughtsTokens,
                                durationMs = result.durationMs,
                            )
                        }

                        is StageAdvance.Rejected -> {
                            TurnLog(
                                index = index,
                                stageBefore = stageBefore,
                                prompt = prompt,
                                reply = result.reply,
                                suggestedNextStage = advance.proposed,
                                judge = result.judge::class.simpleName.orEmpty(),
                                outputTokens = result.usage?.outputTokens,
                                thoughtsTokens = result.usage?.thoughtsTokens,
                                durationMs = result.durationMs,
                                replayErrorReason = "",
                                advanceErrorReason = "NOT ALLOWED"
                            )
                        }
                    }
                }
            }

            println(turnLog.formatted())
            return@mapNotNull turnLog
        }

    private data class TurnLog(
        val index: Int,
        val stageBefore: TaskStage?,
        val prompt: String,
        val reply: String,
        val suggestedNextStage: TaskStage?,
        val judge: String = "",
        val outputTokens: Int? = null,
        val thoughtsTokens: Int? = null,
        val durationMs: Long = 0,
        val replayErrorReason: String = "",
        val advanceErrorReason: String = "",
    ) {
        /** What the turn did to the FSM, derived from the fields already captured. */
        val failed: Boolean get() = replayErrorReason.isNotEmpty()
        val rejected: Boolean get() = advanceErrorReason.isNotEmpty()
        val advanced: Boolean get() = suggestedNextStage != null && !rejected && !failed
        val outcome: String
            get() = when {
                failed -> "FAILED"
                rejected -> "REJECTED"
                advanced -> "ADVANCED"
                else -> "NO_MOVE"
            }

        fun formatted(): String = buildString {
            append("================================= turn $index ================================")
            append("\nstageBefore: ${stageBefore ?: "NO_STAGE"}")
            append(" suggestedNextStage: ${suggestedNextStage ?: "NO_STAGE"}")
            append(" outcome: $outcome")
            append("\ntokens: out=${outputTokens ?: "-"} thoughts=${thoughtsTokens ?: "-"}")
            append(" durationMs=$durationMs judge=${judge.ifEmpty { "-" }}")
            replayErrorReason
                .takeIf { it.isNotEmpty() }
                ?.let { append("\nreplayErrorReason: $it") }
            advanceErrorReason
                .takeIf { it.isNotEmpty() }
                ?.let { append("\nadvanceErrorReason: $it") }

            append("\n- prompt: $prompt")
            reply
                .takeIf { it.isNotEmpty() }
                ?.let { append("\n- reply: ${it.take(300)}...") }
        }
    }

    private data class RunLog(
        val modelId: String,
        val sessionName: String,
        val taskId: String?,
        val finalStage: TaskStage?,
        val turnLogs: List<TurnLog>,
    ) {
        val reachedDone: Boolean get() = finalStage == TaskStage.DONE
        val advances: Int get() = turnLogs.count { it.advanced }
        val rejects: Int get() = turnLogs.count { it.rejected }
        val noMoves: Int get() = turnLogs.count { !it.advanced && !it.rejected && !it.failed }
        val failures: Int get() = turnLogs.count { it.failed }
        val outputTokens: Int get() = turnLogs.sumOf { it.outputTokens ?: 0 }
        val thoughtsTokens: Int get() = turnLogs.sumOf { it.thoughtsTokens ?: 0 }
        val durationMs: Long get() = turnLogs.sumOf { it.durationMs }

        fun formatted(): String = buildString {
            append("---- run [$modelId] task=$taskId ----")
            append("\n  reachedDone=$reachedDone finalStage=${finalStage ?: "NO_STAGE"} turns=${turnLogs.size}")
            append("\n  advances=$advances rejects=$rejects noMoves=$noMoves failures=$failures")
            append("\n  tokens: out=$outputTokens thoughts=$thoughtsTokens durationMs=$durationMs")
        }
    }

    private suspend fun <T> TestScope.withKoinAndTmpFsRoot(block: suspend TestScope.(koin: Koin, tmpFsRoot: File) -> T): T {
        val tempFsRoot = Files.createTempDirectory("project01-stage-live-").toFile()
        try {
            TestDb().use { appDb ->
                val koin = koinApplication {
                    modules(
                        llmModule,
                        networkModule,
                        fileSystemModule,
                        databaseTestModule(appDb.db)
                    )
                }.koin

                return block(koin, tempFsRoot)
            }
        } finally {
            tempFsRoot.deleteRecursively()
        }
    }

    private fun newChat(
        prompt: String,
        session: String?,
        modelProvider: ModelProvider,
        temperature: Double?,
        thinkingBudget: Int? = null
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

    private companion object {
        const val OPENING_PROMPT =
            "Begin the task. I have no requirements beyond the goal — decide the details yourself " +
                    "and move the task forward without asking me questions."

        const val FOLLOW_UP_PROMPT = "continue"

        const val MAX_TURNS = 10

        val SIMPLE_TASK = TaskNotes(
            taskId = "simple-task",
            goal = "Compose a three-item pre-release checklist for a small command-line tool. " +
                    "Each item is one sentence. The checklist text itself is the whole deliverable — " +
                    "no files, no tools, no external systems.",
            stage = TaskStage.CLARIFICATION,
        )
    }
}
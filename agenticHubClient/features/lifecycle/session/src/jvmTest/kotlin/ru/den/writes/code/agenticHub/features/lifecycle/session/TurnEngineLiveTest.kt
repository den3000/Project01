package ru.den.writes.code.agenticHub.features.lifecycle.session

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

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

    private fun runLiveTest(block: suspend TestScope.() -> Unit) = runTest(timeout = LIVE_TIMEOUT) {
        block()
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
            return@withKoinAndTmpFsRoot RunLog(
                modelId = modelProvider.modelId,
                sessionName = sessionName,
                taskId = taskNotes?.taskId,
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
                            )
                        }

                        is StageAdvance.Advanced -> {
                            TurnLog(
                                index = index,
                                stageBefore = stageBefore,
                                prompt = prompt,
                                reply = result.reply,
                                suggestedNextStage = advance.to,
                            )
                        }

                        is StageAdvance.Rejected -> {
                            TurnLog(
                                index = index,
                                stageBefore = stageBefore,
                                prompt = prompt,
                                reply = result.reply,
                                suggestedNextStage = advance.proposed,
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
        val replayErrorReason: String = "",
        val advanceErrorReason: String = "",
    ) {
        fun formatted(): String = buildString {
            append("================================= turn $index ================================")
            append("\nstageBefore: ${stageBefore ?: "NO_STAGE"}")
            append(" suggestedNextStage: ${suggestedNextStage ?: "NO_STAGE"}")
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
        val turnLogs: List<TurnLog>,
    ) {
        fun formatted(): String = buildString {
            append("================================= run ================================")

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
        private val LIVE_TIMEOUT = 10.minutes

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
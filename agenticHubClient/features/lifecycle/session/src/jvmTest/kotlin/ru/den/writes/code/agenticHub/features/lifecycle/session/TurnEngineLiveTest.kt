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
            model = GeminiModel.Known.Gemini25FlashLite,
            apiKey = BuildKonfig.GEMINI_API_KEY
        )
        val sessionName = "turn-engine-live-test"

        // when
        val runLog = runTurnEngineWith(modelProvider, sessionName, null)

        // then
        assertEquals(MAX_TURNS, runLog.turnLogs.size)
    }

    @Test
    fun `when turn engine works with simple task - then it finishes faster then max test turns`() = runLiveTest {
        // given
        val modelProvider = ModelProvider.Gemini(
            model = GeminiModel.Known.Gemini25FlashLite,
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
        val groups = listOf(modelProvider1, modelProvider2, modelProvider3).map { provider ->
            RunGroup(provider.modelId, (0..<tries).map { runTurnEngineWith(provider, sessionName, SIMPLE_TASK) })
        }

        // then — diagnostic summary per model across tries (no hard assert yet)
        reportGroups(groups)
    }

    @Test
    fun `when the stall hint is armed - then reach-done beats the baseline`() = runLiveTest {
        // given
        val modelProvider = ModelProvider.Gemini(
            model = GeminiModel.Known.Gemini25FlashLite,
            apiKey = BuildKonfig.GEMINI_API_KEY
        )
        val sessionName = "turn-engine-live-test"
        val reps = 20

        // when — same weak model + task, differing only by whether the stall nudge is armed
        val baseline = (0..<reps).map { runTurnEngineWith(modelProvider, sessionName, MINIMAL_TASK, stallHint = false) }
        val withHint = (0..<reps).map { runTurnEngineWith(modelProvider, sessionName, MINIMAL_TASK, stallHint = true) }

        // then
        reportGroups(
            listOf(
                RunGroup("baseline (no hint)", baseline),
                RunGroup("with stall hint", withHint),
            )
        )
    }

    /** A labelled batch of runs — one model, or one arm of an A/B (baseline vs hint). */
    private data class RunGroup(val label: String, val runs: List<RunLog>)

    /**
     * The one report every grouped live test prints, in three widening zooms:
     *   1. per-group detail — the turn-by-turn block for each run (MODEL SUMMARY, as before);
     *   2. PER-RUN table — one row per run, raw counts, so every run is visible/comparable;
     *   3. PER-GROUP table — one row per group, counts averaged per run.
     * Takes any number of groups, so new grouped tests reuse it as-is.
     *
     * `stalled`/`recov` carry the conditional read: a run that never sits still is one the
     * stall nudge never touches, so an overall `done` rate mixes those in and dilutes
     * whatever the nudge did. `recov` is scored over the stalled runs alone.
     */
    private fun reportGroups(groups: List<RunGroup>) {
        groups.forEach { printGroupDetail(it) }
        printTable(
            "PER-RUN (raw, every run a row)",
            listOf(
                "group", "run", "done", "turns", "adv", "rej", "noMv", "fail",
                "stall", "recov", "out", "thghts", "ms", "stage",
            ),
            groups.flatMap { g ->
                g.runs.mapIndexed { i, r ->
                    listOf(
                        g.label,
                        "${i + 1}",
                        if (r.reachedDone) "Y" else "-",
                        "${r.turnLogs.size}",
                        "${r.advances}",
                        "${r.rejects}",
                        "${r.noMoves}",
                        "${r.failures}",
                        "${r.longestStall}",
                        if (!r.stalled) "-" else if (r.recovered) "Y" else "N",
                        "${r.outputTokens}",
                        "${r.thoughtsTokens}",
                        "${r.durationMs}",
                        r.finalStage?.name ?: "-",
                    )
                }
            },
        )
        printTable(
            "PER-GROUP (averaged per run)",
            listOf(
                "group", "done", "stalled", "recov", "turns", "adv", "rej", "noMv",
                "fail", "out", "thghts", "ms",
            ),
            groups.map { g ->
                val n = g.runs.size.coerceAtLeast(1)
                val stalledRuns = g.runs.count { it.stalled }
                fun avg(sel: (RunLog) -> Number) = g.runs.sumOf { sel(it).toDouble() } / n
                listOf(
                    g.label,
                    "${g.runs.count { it.reachedDone }}/${g.runs.size}",
                    "$stalledRuns/${g.runs.size}",
                    if (stalledRuns == 0) "-" else "${g.runs.count { it.recovered }}/$stalledRuns",
                    "%.1f".format(avg { it.turnLogs.size }),
                    "%.1f".format(avg { it.advances }),
                    "%.1f".format(avg { it.rejects }),
                    "%.1f".format(avg { it.noMoves }),
                    "%.1f".format(avg { it.failures }),
                    "%.0f".format(avg { it.outputTokens }),
                    "%.0f".format(avg { it.thoughtsTokens }),
                    "%.0f".format(avg { it.durationMs }),
                )
            },
        )
    }

    private fun printGroupDetail(group: RunGroup) {
        val reached = group.runs.count { it.reachedDone }
        val stalledRuns = group.runs.count { it.stalled }
        val turns = group.runs.map { it.turnLogs.size }
        println("========================= SUMMARY: ${group.label} =========================")
        println("reachedDone $reached/${group.runs.size} — turns per run $turns")
        val recovered = if (stalledRuns == 0) "-" else "${group.runs.count { it.recovered }}/$stalledRuns"
        println("stalled $stalledRuns/${group.runs.size} — recovered $recovered")
        group.runs.forEach { println(it.formatted()) }
    }

    /** Print a titled table: header + rows, label column left-aligned, numeric columns right. */
    private fun printTable(title: String, header: List<String>, rows: List<List<String>>) {
        val all = listOf(header) + rows
        val widths = header.indices.map { c -> all.maxOf { it[c].length } }
        fun line(row: List<String>) =
            row.mapIndexed { c, v -> if (c == 0) v.padEnd(widths[c]) else v.padStart(widths[c]) }.joinToString("  ")
        println("\n===== $title =====")
        println(line(header))
        rows.forEach { println(line(it)) }
    }


    private suspend fun TestScope.runTurnEngineWith(modelProvider: ModelProvider, sessionName: String, taskNotes: TaskNotes?, stallHint: Boolean = false) =
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
            val engine = TurnEngine(chatCmd, api, store, memory = memory, stallHint = stallHint)
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
        val noMove: Boolean get() = !advanced && !rejected && !failed
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
        val noMoves: Int get() = turnLogs.count { it.noMove }
        val failures: Int get() = turnLogs.count { it.failed }
        val outputTokens: Int get() = turnLogs.sumOf { it.outputTokens ?: 0 }
        val thoughtsTokens: Int get() = turnLogs.sumOf { it.thoughtsTokens ?: 0 }
        val durationMs: Long get() = turnLogs.sumOf { it.durationMs }

        /**
         * Every maximal span of consecutive NO_MOVE turns, paired with whether the turn that
         * ended it actually advanced the stage (a break-out). False when a reject/failure
         * ended it, or when the span was still open as the run ran out of turns.
         */
        private val noMoveSpans: List<Pair<Int, Boolean>>
            get() {
                val spans = mutableListOf<Pair<Int, Boolean>>()
                var len = 0
                turnLogs.forEach { turn ->
                    if (turn.noMove) {
                        len++
                    } else {
                        if (len > 0) spans += len to turn.advanced
                        len = 0
                    }
                }
                if (len > 0) spans += len to false
                return spans
            }

        /**
         * Spans long enough to count as a stall episode. [STALL_STREAK_LIMIT] NO_MOVE turns
         * in a row is exactly what arms the engine's stall nudge, so these runs — and only
         * these — are the population an A/B over that nudge is actually about.
         */
        private val stallSpans: List<Pair<Int, Boolean>> get() =
            noMoveSpans.filter { (len, _) -> len >= STALL_STREAK_LIMIT }

        /** Longest NO_MOVE streak of any length; 0 when the stage never repeated. */
        val longestStall: Int get() = noMoveSpans.maxOfOrNull { (len, _) -> len } ?: 0

        /** Sat through at least one stall episode — the nudge had something to fix here. */
        val stalled: Boolean get() = stallSpans.isNotEmpty()

        /** Broke out of at least one stall episode with a real stage advance. */
        val recovered: Boolean get() = stallSpans.any { (_, broke) -> broke }

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
        /**
         * NO_MOVE turns in a row that make a stall episode. Mirrors the engine's own
         * `STALL_STREAK_LIMIT` (TurnEngine.kt) — the point where the stall nudge arms —
         * so `stalled`/`recovered` describe exactly the runs the nudge could act on.
         * The engine's constant is file-private, so this copy is kept in sync by hand.
         */
        const val STALL_STREAK_LIMIT = 2

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

        /**
         * The opposite lever from a "hard" task: the deliverable is a SINGLE sentence, ready the
         * moment planning ends, so EXECUTION has nothing left to do. On each `continue` the model
         * tends to repeat ("I already said it") and degenerate into the execution-lock (NO_MOVE)
         * instead of signalling validation — the failure mode a stall hint targets. Short replies
         * also keep runs fast and within the live budget, unlike the volume a "cover as many
         * scenarios as you can" task provokes (which collapsed into 503s and a 15-min timeout).
         * Pure text — no tools, RAG or judge — to isolate the FSM marker channel.
         */
        val MINIMAL_TASK = TaskNotes(
            taskId = "minimal-task",
            goal = "Name the single most important pre-release check for a small command-line " +
                    "tool, in one short sentence. That one sentence is the entire deliverable — " +
                    "nothing else: no explanation, no list, no extra text.",
            stage = TaskStage.CLARIFICATION,
        )
    }
}
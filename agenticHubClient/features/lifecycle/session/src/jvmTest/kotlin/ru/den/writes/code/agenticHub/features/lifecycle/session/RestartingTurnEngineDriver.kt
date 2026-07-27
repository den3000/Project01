package ru.den.writes.code.agenticHub.features.lifecycle.session

import kotlinx.coroutines.test.TestScope
import org.koin.core.Koin
import ru.den.writes.code.agenticHub.features.agent.RoutedAgent
import ru.den.writes.code.agenticHub.features.agent.RoutedJudge
import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.TurnEngine
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.buildLlmApi
import ru.den.writes.code.agenticHub.features.memory.FileMemoryStore
import ru.den.writes.code.agenticHub.features.memory.MemoryMode
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import ru.den.writes.code.agenticHub.features.memory.db.RoomHistoryStore
import ru.den.writes.code.agenticHub.platform.database.MessageDao

/**
 * The same run as `runTurnEngineWith`, except this driver lets a run restart itself and
 * measures what the restarts did.
 *
 * The restart is carried out by the engine — the task goes back to the beginning and the
 * conversation moves to a fresh branch, both inside the turn that decided it. What is left
 * for a driver is bookkeeping: keep feeding turns until the task is done or has given up,
 * and record what the restarts did on the way. [TurnResult.fsm] is read as a report, and the
 * same reading serves whoever owns the loop in production.
 *
 * A deliberate copy of the plain driver rather than a flag on it: the two answer different
 * questions ("how does one attempt go" vs "how does a run with restarts go"), and a stand
 * that measures restarts should not be one boolean away from the stand that measures the
 * engine without them.
 */
internal suspend fun TestScope.runRestartingTurnEngineWith(
    llmApi: (Koin) -> LlmApi,
    engineUnderTest: EngineUnderTest = FSM_ENGINE,
    task: TaskNotes,
    prompt: String = OPENING_PROMPT,
    followUpPrompt: String = FOLLOW_UP_PROMPT,
    routedAgents: List<RoutedAgent> = emptyList(),
    routedJudges: List<RoutedJudge> = emptyList(),
    modelProvider: ModelProvider = dummyProvider(),
    sessionName: String = "s",
    temperature: Double? = null,
    logTurns: Boolean = false,
): RestartingRunLog = withSessionEnv { koin, fsRoot ->
    val memStore = FileMemoryStore(fsRoot.absolutePath, fs = koin.get())
    memStore.saveTask(task)
    val dao = koin.get<MessageDao>()
    val historyStore = RoomHistoryStore(dao, sessionId = sessionName)
    val deps = EngineDeps(
        cliArgs = newChat(prompt, sessionName, modelProvider, temperature),
        llmApi = llmApi(koin),
        historyStore = historyStore,
        memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = task.taskId),
        routedAgents = routedAgents,
        routedJudges = routedJudges,
        stallHint = false,
    )

    val engine: TurnEngine = engineUnderTest.create(deps)
    val turnLogs = mutableListOf<TurnLog>()
    val restarts = mutableListOf<RestartLog>()
    var attempt = 1
    var index = 0

    while (memStore.loadTask(task.taskId)?.stage != TaskStage.DONE) {
        val stageBefore = memStore.loadTask(task.taskId)?.stage
        val turnPrompt = if (index == 0) prompt else followUpPrompt
        val log = TurnLog(index, stageBefore, turnPrompt, engine.turn(turnPrompt))
        turnLogs += log
        if (logTurns) println(log.formatted())

        when (val verdict = log.result.retryOutcome) {
            is RetryOutcome.Restarted -> {
                attempt++
                // The engine has already emptied the wire; the driver only records where
                // the attempt that just died had got to.
                restarts += RestartLog(afterTurn = index, attempt = attempt, stage = stageBefore)
                if (logTurns) println(">>> RESTART after turn $index — attempt $attempt, branch ${historyStore.branchId}")
            }

            is RetryOutcome.GaveUp -> {
                restarts += RestartLog(afterTurn = index, attempt = attempt, stage = stageBefore, gaveUp = true)
                if (logTurns) println(">>> GAVE UP after turn $index — reason ${verdict.reason}")
                break
            }

            else -> Unit
        }
        index++
    }

    RestartingRunLog(
        run = RunLog(
            modelId = modelProvider.modelId,
            sessionName = sessionName,
            taskId = task.taskId,
            finalStage = memStore.loadTask(task.taskId)?.stage,
            turnLogs = turnLogs,
            persistedMessages = RoomHistoryStore(dao, sessionId = sessionName, initialBranch = historyStore.branchId)
                .apply { load() }.messages,
            persistedCount = dao.count(),
        ),
        restarts = restarts,
        attempts = attempt,
    )
}

/** The live face of [runRestartingTurnEngineWith], mirroring the plain driver's. */
internal suspend fun TestScope.runRestartingTurnEngineWith(
    modelProvider: ModelProvider,
    sessionName: String,
    task: TaskNotes,
    engineUnderTest: EngineUnderTest = FSM_ENGINE,
): RestartingRunLog = runRestartingTurnEngineWith(
    llmApi = { koin -> buildLlmApi(modelProvider, koin.get()) },
    engineUnderTest = engineUnderTest,
    task = task,
    modelProvider = modelProvider,
    sessionName = sessionName,
    logTurns = true,
)

/** One restart (or the give-up that ended the run), and where it happened. */
internal data class RestartLog(
    val afterTurn: Int,
    val attempt: Int,
    val stage: TaskStage?,
    val gaveUp: Boolean = false,
)

/**
 * A run that was allowed to restart itself: the usual [RunLog] plus what the restarts did.
 *
 * [RunLog.turnLogs] spans every attempt — the tokens a restarted run costs are the tokens of
 * all of them — while [RunLog.persistedMessages] is only the branch the run ended on, which
 * is what the next session would load.
 */
internal data class RestartingRunLog(
    val run: RunLog,
    val restarts: List<RestartLog>,
    val attempts: Int,
) {
    val reachedDone: Boolean get() = run.reachedDone
    val gaveUp: Boolean get() = restarts.any { it.gaveUp }

    /** Turns spent on the attempt that finished — the cost of the last try alone. */
    val turnsOnLastAttempt: Int
        get() = run.turnLogs.size - (restarts.lastOrNull { !it.gaveUp }?.afterTurn?.plus(1) ?: 0)

    fun formatted(): String = buildString {
        append(run.formatted())
        append("\n  attempts=$attempts restarts=${restarts.count { !it.gaveUp }} gaveUp=$gaveUp")
        restarts.forEach {
            append("\n   - ${if (it.gaveUp) "gave up" else "restart"} after turn ${it.afterTurn}")
            append(" at stage ${it.stage ?: "NO_STAGE"} (attempt ${it.attempt})")
        }
    }
}

/**
 * The report for restarting runs: the per-run table of the plain stand plus what restarting
 * added — how many attempts a run took, where it was when it gave up, and how much of the
 * turn count went on the attempt that finally landed.
 */
internal fun reportRestartingRuns(label: String, runs: List<RestartingRunLog>) {
    println("========================= SUMMARY: $label =========================")
    println("reachedDone ${runs.count { it.reachedDone }}/${runs.size} — gaveUp ${runs.count { it.gaveUp }}")
    runs.forEach { println(it.formatted()) }
    reportGroups(listOf(RunGroup(label, runs.map { it.run })))
}

package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.den.writes.code.project01.cliJvm.command.ScheduleSpec
import ru.den.writes.code.project01.cliJvm.command.StartCommand
import ru.den.writes.code.agenticHub.features.memory.db.HistoryStore
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.scheduling.InMemoryScheduleStore
import ru.den.writes.code.agenticHub.scheduling.SchedulerEngine
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.ToolDefinition
import ru.den.writes.code.agenticHub.features.llm.ToolExecutor

/** Scheduler cadence: how often to check for due tasks, and how often to publish a report. */
private const val SCHEDULER_TICK_MS = 1_000L
private const val SCHEDULER_REPORT_MS = 30_000L

/**
 * The MVI stack assembled for one session, plus its optional scheduler machinery.
 * The composition root drives a renderer over [viewModel] and (when present) runs
 * the scheduler loops via [startSchedulerLoops].
 */
public class SessionAssembly(
    public val viewModel: SessionViewModel,
    public val scheduler: SchedulerEngine?,
    public val control: SchedulerControl?,
    public val schedules: List<ScheduleSpec>,
)

/**
 * Assemble the MVI stack — [TurnEngine] + [CommandRunner] + [SessionViewModel] —
 * plus, when the config carries schedules, a [SchedulerEngine] + [SchedulerControl].
 * The scheduler is built before the view-model so the REPL adds to the same engine;
 * the handler's `submitTurn` is wired once the view-model exists (breaking the
 * vm↔scheduler construction cycle). Portable — no renderer / CLI dependency.
 */
public fun buildSessionViewModel(
    cliArgs: StartCommand.SessionInitialState,
    llmApi: LlmApi,
    historyStore: HistoryStore?,
    strategy: ContextStrategy,
    memory: MemoryProvider?,
    routedAgents: List<RoutedAgent>,
    routedJudges: List<RoutedJudge>,
    toolDefs: List<ToolDefinition> = emptyList(),
    toolExecutor: ToolExecutor? = null,
): SessionAssembly {
    val multiAgent = routedAgents.isNotEmpty()
    val schedules = (cliArgs as? StartCommand.RunChat)?.config?.schedules.orEmpty()
    val schedulerEnabled = schedules.isNotEmpty()
    val engine = TurnEngine(
        cliArgs, llmApi, historyStore, strategy, memory, routedAgents, routedJudges, toolDefs, toolExecutor,
    )

    val actions = mutableMapOf<String, ScheduleAction>()
    val handler = CliTaskHandler(actions, toolExecutor)
    val scheduler = if (schedulerEnabled) {
        SchedulerEngine(InMemoryScheduleStore(), handler, now = { System.currentTimeMillis() })
    } else {
        null
    }
    val control = scheduler?.let { SchedulerControl(it, actions) }

    val commandRunner = CommandRunner(historyStore, memory, strategy, control)
    val viewModel = SessionViewModel(
        cliArgs, engine, commandRunner, historyStore, memory, strategy, multiAgent,
        schedulerEnabled = schedulerEnabled,
    )
    handler.submitTurn = viewModel::submitFromScheduler
    return SessionAssembly(viewModel, scheduler, control, schedules)
}

/**
 * Launch the scheduler loops on `Dispatchers.IO`: a ticker that adds the startup tasks
 * (via [control], filling the handler's action map) then fires due tasks, and a reporter
 * that posts an aggregated report as a feed line every [SCHEDULER_REPORT_MS].
 */
public fun CoroutineScope.startSchedulerLoops(
    engine: SchedulerEngine,
    control: SchedulerControl,
    schedules: List<ScheduleSpec>,
    vm: SessionViewModel,
): List<Job> {
    val ticker = launch(Dispatchers.IO) {
        for (spec in schedules) control.add(spec)
        engine.runLoop(SCHEDULER_TICK_MS)
    }
    val reporter = launch(Dispatchers.IO) {
        // Baseline = the current (usually empty) summary, so we never announce "No results yet.":
        // post only when it CHANGES — collect tasks show progress, agent-only stays quiet, and a
        // cancelled schedule goes silent (the summary stops moving).
        var last = engine.summary()
        while (isActive) {
            delay(SCHEDULER_REPORT_MS)
            val summary = engine.summary()
            if (summary != last) {
                vm.postNotice(summary)
                last = summary
            }
        }
    }
    return listOf(ticker, reporter)
}

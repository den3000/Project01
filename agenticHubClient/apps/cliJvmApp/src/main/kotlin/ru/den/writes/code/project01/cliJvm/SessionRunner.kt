package ru.den.writes.code.project01.cliJvm

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.den.writes.code.project01.cliJvm.command.CliArgToSessionCommandMapper
import ru.den.writes.code.project01.cliJvm.command.ScheduleSpec
import ru.den.writes.code.project01.cliJvm.command.StartCommand
import ru.den.writes.code.project01.cliJvm.db.AppDatabase
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.cliJvm.plain.PlainRenderer
import ru.den.writes.code.project01.cliJvm.tui.TuiRenderer
import ru.den.writes.code.project01.scheduling.InMemoryScheduleStore
import ru.den.writes.code.project01.scheduling.SchedulerEngine
import ru.den.writes.code.project01.shared.llm.LlmApi
import ru.den.writes.code.project01.shared.llm.buildLlmApi
import ru.den.writes.code.project01.shared.llm.ToolDefinition
import ru.den.writes.code.project01.shared.llm.ToolExecutor
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Scheduler cadence: how often to check for due tasks, and how often to publish a report. */
private const val SCHEDULER_TICK_MS = 1_000L
private const val SCHEDULER_REPORT_MS = 30_000L

/**
 * Prepare the objects a session runs on and hand off to [runSessionInternal].
 * Both chat and one-shot come through here; they differ only in whether they own
 * a [HistoryStore]. Collaborators are built via the session accessors
 * ([contextStrategy]/[memoryProvider]/[historyStore]) and builders
 * ([buildLlmApi]/[buildRoutedAgents]/…) over the shared [client] (whose lifecycle
 * the caller owns). Chat may swap stdin for a file-feed source; that reader's
 * lifecycle is bounded by `use { }`.
 */
internal suspend fun runSession(
    client: HttpClient,
    db: AppDatabase,
    initialState: StartCommand.SessionInitialState,
    sessionMapper: CliArgToSessionCommandMapper,
) {
    val historyStore: HistoryStore? = initialState.historyStore(db)
    val llmApi: LlmApi = buildLlmApi(initialState.modelProvider, client)
    val chat = initialState as? StartCommand.RunChat
    val strategy: ContextStrategy = initialState.contextStrategy()
    val memory: MemoryProvider? = initialState.memoryProvider()
    val routedAgents: List<RoutedAgent> = buildRoutedAgents(chat, client, initialState.toGenerationParams())
    val routedJudges: List<RoutedJudge> = buildJudges(chat, client)
    val mcpClients: List<McpToolClient> = buildMcpToolClients(chat).onEach { it.connect() }
    val router: McpToolRouter? = buildToolRouter(mcpClients, chat)
    val toolDefs = router?.toolDefs.orEmpty()

    try {
        val feedFile = (initialState as? StartCommand.RunChat)?.config?.feedFile
        if (feedFile != null) {
            // File-driven feed: open the reader, hand a feed source to the session
            // (line-by-line or fixed chunks), then REPL after EOF. `use` closes the
            // reader when the session returns.
            File(feedFile).bufferedReader(Charsets.UTF_8).use { reader ->
                val feedSource: PromptSource = if (initialState.config.byLine) {
                    LineFilePromptSource(reader = reader, instruction = initialState.config.feedInstruction)
                } else {
                    ChunkedFilePromptSource(
                        reader = reader,
                        chunkChars = initialState.config.chunkChars,
                        instruction = initialState.config.feedInstruction,
                    )
                }
                val stdinAfter = StdinPromptSource(
                    java.io.BufferedReader(java.io.InputStreamReader(System.`in`)),
                    sessionMapper,
                )
                runSessionInternal(
                    cliArgs = initialState,
                    llmApi = llmApi,
                    historyStore = historyStore,
                    strategy = strategy,
                    memory = memory,
                    routedAgents = routedAgents,
                    routedJudges = routedJudges,
                    primary = feedSource,
                    replAfterFeed = stdinAfter,
                    toolDefs = toolDefs,
                    toolExecutor = router,
                    sessionMapper = sessionMapper,
                )
            }
        } else {
            // Stdin REPL — TUI when -tui and a real TTY, else plain.
            val tuiRequested = (initialState as? StartCommand.RunChat)?.config?.tui ?: false
            runSessionInternal(
                cliArgs = initialState,
                llmApi = llmApi,
                historyStore = historyStore,
                strategy = strategy,
                memory = memory,
                routedAgents = routedAgents,
                routedJudges = routedJudges,
                primary = StdinPromptSource(
                    java.io.BufferedReader(java.io.InputStreamReader(System.`in`)),
                    sessionMapper,
                ),
                view = pickView(tuiRequested, System.console() != null),
                toolDefs = toolDefs,
                toolExecutor = router,
                sessionMapper = sessionMapper,
            )
        }
    } finally {
        mcpClients.forEach { it.close() }
    }
}

/**
 * Assemble the MVI stack — [TurnEngine] + [SessionViewModel] + a renderer — and
 * run it over [primary] (with an optional feed→REPL [replAfterFeed]). The 16s
 * throttle applies only to a feed source; interactive stdin runs full speed.
 */
internal suspend fun runSessionInternal(
    cliArgs: StartCommand.SessionInitialState,
    llmApi: LlmApi,
    historyStore: HistoryStore?,
    strategy: ContextStrategy,
    memory: MemoryProvider?,
    routedAgents: List<RoutedAgent>,
    routedJudges: List<RoutedJudge>,
    primary: PromptSource,
    replAfterFeed: PromptSource? = null,
    view: ViewKind = ViewKind.PLAIN,
    toolDefs: List<ToolDefinition> = emptyList(),
    toolExecutor: ToolExecutor? = null,
    sessionMapper: CliArgToSessionCommandMapper,
) {
    val multiAgent = routedAgents.isNotEmpty()
    val schedules = (cliArgs as? StartCommand.RunChat)?.config?.schedules.orEmpty()
    val schedulerEnabled = schedules.isNotEmpty()
    val engine = TurnEngine(
        cliArgs, llmApi, historyStore, strategy, memory, routedAgents, routedJudges, toolDefs, toolExecutor,
    )

    // Scheduler shared by startup -schedule and in-session /schedule. Built before the
    // command runner / view-model so the REPL adds to the same engine; the handler's
    // submitTurn is wired once the view-model exists (breaking the construction cycle).
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

    coroutineScope {
        val schedulerJobs =
            if (scheduler != null && control != null) startSchedulerLoops(scheduler, control, schedules, viewModel)
            else emptyList()
        try {
            when (view) {
                ViewKind.TUI -> TuiRenderer(sessionMapper).run(viewModel, ChannelIntentSource())
                ViewKind.PLAIN -> {
                    val feedThrottle = if (replAfterFeed != null) 16.seconds else Duration.ZERO
                    PlainRenderer().run(
                        viewModel,
                        PromptSourceIntents(primary, feedThrottle),
                        replAfterFeed?.let { PromptSourceIntents(it) },
                    )
                }
            }
        } finally {
            schedulerJobs.forEach { it.cancel() }
            viewModel.closeSchedulerInbox()
        }
    }
}

/**
 * Launch the scheduler loops on `Dispatchers.IO`: a ticker that adds the startup tasks
 * (via [control], filling the handler's action map) then fires due tasks, and a reporter
 * that posts an aggregated report as a feed line every [SCHEDULER_REPORT_MS].
 */
private fun CoroutineScope.startSchedulerLoops(
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

/** Which renderer drives a session. */
internal enum class ViewKind { TUI, PLAIN }

/**
 * TUI only for an opted-in chat on a real TTY; feed / one-shot / non-TTY (pipe,
 * IDE, CI) all render plain. Pure so the choice is unit-testable.
 */
internal fun pickView(tui: Boolean, hasConsole: Boolean): ViewKind =
    if (tui && hasConsole) ViewKind.TUI else ViewKind.PLAIN

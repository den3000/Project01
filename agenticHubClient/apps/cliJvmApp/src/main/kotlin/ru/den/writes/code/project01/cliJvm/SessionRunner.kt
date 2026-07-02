package ru.den.writes.code.project01.cliJvm

import io.ktor.client.HttpClient
import kotlinx.coroutines.coroutineScope
import ru.den.writes.code.project01.cliJvm.commandMappers.CliArgToSessionCommandMapper
import ru.den.writes.code.project01.cliJvm.command.StartCommand
import ru.den.writes.code.agenticHub.platform.database.AppDatabase
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.cliJvm.plain.PlainRenderer
import ru.den.writes.code.project01.cliJvm.tui.TuiRenderer
import ru.den.writes.code.project01.shared.llm.LlmApi
import ru.den.writes.code.project01.shared.llm.buildLlmApi
import ru.den.writes.code.project01.shared.llm.ToolDefinition
import ru.den.writes.code.project01.shared.llm.ToolExecutor
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
    val memory: MemoryProvider? = initialState.memoryProvider(MEMORY_ROOT.absolutePath)
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
 * Drive a renderer over the assembled MVI stack ([buildSessionViewModel]), running
 * the scheduler loops alongside it when the config carries schedules. The renderer
 * (TUI vs plain) and the 16s feed throttle are the CLI-specific bits that keep this
 * function in the app; the portable assembly lives in features:viewModel.
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
    val assembly = buildSessionViewModel(
        cliArgs, llmApi, historyStore, strategy, memory, routedAgents, routedJudges, toolDefs, toolExecutor,
    )
    val viewModel = assembly.viewModel

    val scheduler = assembly.scheduler
    val control = assembly.control
    coroutineScope {
        val schedulerJobs =
            if (scheduler != null && control != null) {
                startSchedulerLoops(scheduler, control, assembly.schedules, viewModel)
            } else {
                emptyList()
            }
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

/** Which renderer drives a session. */
internal enum class ViewKind { TUI, PLAIN }

/**
 * TUI only for an opted-in chat on a real TTY; feed / one-shot / non-TTY (pipe,
 * IDE, CI) all render plain. Pure so the choice is unit-testable.
 */
internal fun pickView(tui: Boolean, hasConsole: Boolean): ViewKind =
    if (tui && hasConsole) ViewKind.TUI else ViewKind.PLAIN

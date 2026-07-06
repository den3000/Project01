package ru.den.writes.code.agenticHub.cliJvm

import kotlinx.coroutines.coroutineScope
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf
import ru.den.writes.code.agenticHub.features.lifecycle.start.MEMORY_ROOT
import ru.den.writes.code.agenticHub.features.lifecycle.start.RAG_ROOT
import ru.den.writes.code.agenticHub.features.lifecycle.session.RagControl
import ru.den.writes.code.agenticHub.features.rag.embedding.EmbedderKind
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.toGenerationParams
import ru.den.writes.code.agenticHub.features.lifecycle.session.startSchedulerLoops
import ru.den.writes.code.agenticHub.cliJvm.commandMappers.resolveMemoryProvider
import ru.den.writes.code.agenticHub.cliJvm.commandMappers.contextStrategy
import ru.den.writes.code.agenticHub.cliJvm.commandMappers.resolveHistoryStore
import ru.den.writes.code.agenticHub.features.lifecycle.session.SessionAssembly
import ru.den.writes.code.agenticHub.features.lifecycle.session.di.SessionAssemblyArgs
import ru.den.writes.code.agenticHub.features.agent.RoutedJudge
import ru.den.writes.code.agenticHub.features.agent.RoutedAgent
import ru.den.writes.code.agenticHub.features.lifecycle.session.intents.PromptSourceIntents
import ru.den.writes.code.agenticHub.features.lifecycle.session.PromptSource
import ru.den.writes.code.agenticHub.features.llm.McpToolRouter
import ru.den.writes.code.agenticHub.features.memory.ContextStrategy
import ru.den.writes.code.agenticHub.features.lifecycle.session.intents.ChannelIntentSource
import ru.den.writes.code.agenticHub.features.mcpclient.McpToolClient
import ru.den.writes.code.agenticHub.cliJvm.commandMappers.CliArgToSessionCommandMapper
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.features.memory.db.HistoryStore
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.cliJvm.plain.PlainRenderer
import ru.den.writes.code.agenticHub.cliJvm.tui.TuiRenderer
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.ToolDefinition
import ru.den.writes.code.agenticHub.features.llm.ToolExecutor
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
    koin: Koin,
    initialState: StartCommand.SessionInitialState,
    sessionMapper: CliArgToSessionCommandMapper,
) {
    val chat = initialState as? StartCommand.RunChat
    val historyStore: HistoryStore? = initialState.resolveHistoryStore(koin)
    val llmApi: LlmApi = koin.get { parametersOf(initialState.modelProvider) }
    val strategy: ContextStrategy = initialState.contextStrategy()
    val memory: MemoryProvider? = initialState.resolveMemoryProvider(koin, MEMORY_ROOT)
    val routedAgents: List<RoutedAgent> =
        koin.get { parametersOf(chat?.config?.stageAgents.orEmpty(), initialState.toGenerationParams()) }
    val routedJudges: List<RoutedJudge> =
        koin.get { parametersOf(chat?.config?.judgeAgents.orEmpty()) }
    val mcpClients: List<McpToolClient> = buildMcpToolClients(koin, chat).onEach { it.connect() }
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
                    koin = koin,
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
                koin = koin,
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
    koin: Koin,
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
    val ragEmbedder = (cliArgs as? StartCommand.RunChat)?.config?.ragEmbedder ?: EmbedderKind.OLLAMA
    val ragControl = RagControl(
        indexStore = koin.get(), embedderSelector = koin.get(), ragRoot = RAG_ROOT, defaultKind = ragEmbedder,
    )
    val assembly = koin.get<SessionAssembly> {
        parametersOf(
            SessionAssemblyArgs(
                cliArgs, llmApi, historyStore, strategy, memory,
                routedAgents, routedJudges, toolDefs, toolExecutor, ragControl,
            ),
        )
    }
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

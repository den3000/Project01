package ru.den.writes.code.project01.cliJvm

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import ru.den.writes.code.project01.cliJvm.command.CliCommand
import ru.den.writes.code.project01.cliJvm.command.MemoryAction
import ru.den.writes.code.project01.cliJvm.command.ScheduleSpec
import ru.den.writes.code.project01.cliJvm.db.AppDatabase
import ru.den.writes.code.project01.cliJvm.db.DEFAULT_BRANCH
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.db.MessageDao
import ru.den.writes.code.project01.cliJvm.db.MessageEntity
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.cliJvm.memory.MemoryStore
import ru.den.writes.code.project01.cliJvm.plain.PlainRenderer
import ru.den.writes.code.project01.cliJvm.tui.TuiRenderer
import ru.den.writes.code.project01.scheduling.InMemoryScheduleStore
import ru.den.writes.code.project01.scheduling.Schedule
import ru.den.writes.code.project01.scheduling.SchedulerEngine
import ru.den.writes.code.project01.shared.agent.AgentConfig
import ru.den.writes.code.project01.shared.agent.AgentResponder
import ru.den.writes.code.project01.shared.context.HistoryCompressor
import ru.den.writes.code.project01.shared.invariant.LlmInvariantJudge
import ru.den.writes.code.project01.shared.llm.LlmApi
import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.shared.llm.ToolDefinition
import ru.den.writes.code.project01.shared.llm.ToolExecutor
import ru.den.writes.code.project01.shared.llm.Usage
import ru.den.writes.code.project01.shared.llm.gemini.GeminiApi
import ru.den.writes.code.project01.shared.llm.huggingface.HuggingFaceApi
import ru.den.writes.code.project01.shared.llm.openrouter.OpenRouterApi
import ru.den.writes.code.project01.shared.memory.ProfileSection
import ru.den.writes.code.project01.shared.memory.TaskNotes
import ru.den.writes.code.project01.shared.memory.TaskStage
import ru.den.writes.code.project01.shared.pricing.PricingRegistry
import java.io.File
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Generous request timeout — LLM responses can take a while. */
private const val REQUEST_TIMEOUT_MS = 300_000L

/** Scheduler cadence: how often to check for due tasks, and how often to publish a report. */
private const val SCHEDULER_TICK_MS = 1_000L
private const val SCHEDULER_REPORT_MS = 30_000L

/**
 * Root of the on-disk memory layer. Profile, rules and task notes live under
 * this folder as markdown files — see [MemoryStore] for the layout.
 */
private val MEMORY_ROOT: File = File(
    System.getProperty("user.home"),
    ".project01-cli/memory",
)

/**
 * Runs a parsed [CliCommand] against the runtime — the "how" to the parser's
 * "what". Holds the execution logic lifted out of `main`: list / clean /
 * inflate / memory ops (no LLM, no app runtime) and the chat / one-shot path
 * (HTTP client + MVI stack). Owns only the [db]; the HTTP client is opened per
 * prompt-command and closed with it.
 */
internal class CommandExecutor(private val db: AppDatabase) {

    suspend fun run(command: CliCommand) {
        when (command) {
            is CliCommand.ListSessions -> printSessionList(db.messageDao())
            is CliCommand.CleanHistory -> cleanHistory()
            is CliCommand.CleanSession -> cleanSession(command.sessionId)
            is CliCommand.InflateSession -> inflateSession(command)
            is CliCommand.MemoryOp -> handleMemoryCommand(command.action)
            is CliCommand.RunPrompt -> runPromptCommand(command)
        }
    }

    /** Wipe every messages / summaries / facts row — otherwise an orphan row would resurrect on a reused session id. */
    private suspend fun cleanHistory() {
        val before = db.messageDao().count()
        db.messageDao().clearAll()
        db.messageDao().clearAllSummaries()
        db.messageDao().clearAllFacts()
        println("Cleared $before messages across all sessions (and any saved summaries / facts).")
    }

    /** Wipe one session's rows by name — the per-session twin of [cleanHistory]. */
    private suspend fun cleanSession(sessionId: String) {
        val dao = db.messageDao()
        val before = dao.countSession(sessionId)
        dao.deleteSessionMessages(sessionId)
        dao.deleteSessionSummaries(sessionId)
        dao.deleteSessionFacts(sessionId)
        println("Cleared $before messages from session '$sessionId' (and any saved summary / facts).")
    }

    /**
     * Run a memory invocation against the on-disk memory root. Pure disk
     * operation — no LLM, no session, no DB. Prints a short status line to
     * stdout (errors to stderr) and returns.
     */
    private fun handleMemoryCommand(action: MemoryAction) {
        MEMORY_ROOT.mkdirs()
        val store = MemoryStore(MEMORY_ROOT)
        when (action) {
            is MemoryAction.Show -> {
                // Temporary provider in PREAMBLE mode for describe() — no task
                // is active from the CLI; prints the dormant snapshot of every layer.
                println(MemoryProvider(store, initialTaskId = null).describe())
            }
            is MemoryAction.AddProfileItem -> {
                val updated = store.addProfileItem(action.section, action.text)
                val count = updated.items(action.section).size
                println("[memory] profile.${action.section.keyword} += \"${action.text}\" ($count item(s) total)")
            }
            is MemoryAction.ClearProfileSection -> {
                store.clearProfileSection(action.section)
                println("[memory] profile.${action.section.keyword} cleared")
            }
            is MemoryAction.ClearProfile -> {
                store.clearProfile()
                println("[memory] profile cleared")
            }
            is MemoryAction.ListProfiles -> {
                val names = store.listProfileNames()
                if (names.isEmpty()) println("[memory] no named profiles")
                else {
                    println("[memory] profiles:")
                    names.forEach { println("  - $it") }
                }
            }
            is MemoryAction.ShowProfile -> {
                val data = store.loadNamedProfile(action.name)
                if (data == null) {
                    System.err.println("[memory] profile '${action.name}' is empty or absent")
                } else {
                    println("[profile:${action.name}]")
                    data.freeText?.takeIf { it.isNotBlank() }?.let { println(it.trim()) }
                    for (section in ProfileSection.entries) {
                        val items = data.items(section)
                        if (items.isEmpty()) continue
                        println("${section.keyword}: ${items.joinToString(", ")}")
                    }
                }
            }
            is MemoryAction.TouchProfile -> {
                store.touchNamedProfile(action.name)
                println("[memory] profile '${action.name}' ready under ${File(MEMORY_ROOT, MemoryStore.PROFILES_DIR).absolutePath}/${action.name}.md")
            }
            is MemoryAction.AddNamedProfileItem -> {
                val updated = store.addNamedProfileItem(action.name, action.section, action.text)
                val count = updated.items(action.section).size
                println("[memory] profile.${action.name}.${action.section.keyword} += \"${action.text}\" ($count item(s) total)")
            }
            is MemoryAction.ClearNamedProfileSection -> {
                store.clearNamedProfileSection(action.name, action.section)
                println("[memory] profile.${action.name}.${action.section.keyword} cleared")
            }
            is MemoryAction.ClearNamedProfile -> {
                val removed = store.clearNamedProfile(action.name)
                if (removed) println("[memory] profile '${action.name}' removed")
                else System.err.println("[memory] no profile named '${action.name}'")
            }
            is MemoryAction.ClearAllProfiles -> {
                val n = store.clearAllProfiles()
                println("[memory] all profiles cleared ($n named + unnamed)")
            }
            is MemoryAction.AddRule -> {
                val rule = store.addRule(action.text)
                println("[memory] rule ${rule.id} added")
            }
            is MemoryAction.RemoveRule -> {
                val removed = store.removeRule(action.id)
                if (removed) println("[memory] rule ${action.id} removed")
                else System.err.println("[memory] no rule with id '${action.id}'")
            }
            is MemoryAction.ClearRules -> {
                val n = store.clearRules()
                println("[memory] cleared $n rule(s)")
            }
            is MemoryAction.SetTask -> {
                // Touch-create so subsequent show (and the next chat with the task)
                // sees a file rather than nothing. A new task starts at the initial stage.
                if (store.loadTask(action.taskId) == null) {
                    store.saveTask(TaskNotes(taskId = action.taskId, stage = TaskStage.INITIAL))
                }
                println("[memory] task '${action.taskId}' ready under ${File(MEMORY_ROOT, MemoryStore.TASKS_DIR).absolutePath}/${action.taskId}.md")
            }
            is MemoryAction.PauseTask -> setTaskPaused(store, action.taskId, paused = true)
            is MemoryAction.ResumeTask -> setTaskPaused(store, action.taskId, paused = false)
            is MemoryAction.DeleteTask -> {
                val removed = store.deleteTask(action.taskId)
                if (removed) println("[memory] task '${action.taskId}' deleted")
                else System.err.println("[memory] no task '${action.taskId}'")
            }
            is MemoryAction.ClearTasks -> {
                val n = store.clearTasks()
                println("[memory] cleared $n task(s)")
            }
        }
    }

    /**
     * Pure-disk pause/resume. Loads (or touch-creates at the initial stage) the
     * task, flips its `paused` flag, writes it back. A paused task holds its
     * stage — the chat agent's auto-advance skips it — so it can be parked here.
     */
    private fun setTaskPaused(store: MemoryStore, taskId: String, paused: Boolean) {
        val task = store.loadTask(taskId) ?: TaskNotes(taskId = taskId, stage = TaskStage.INITIAL)
        store.saveTask(task.copy(paused = paused))
        val word = if (paused) "paused" else "resumed"
        println("[memory] task '$taskId' $word (stage ${task.stage?.keyword ?: "(none)"})")
    }

    /**
     * Duplicates the last N rows of the given session in-place. No LLM call, no
     * network — pure DB ALTER. Copies carry just `text` + `role`; `model_id` and
     * token counts are cleared so [SessionStats] doesn't double-count usage that
     * was already billed. The next real turn sees the inflated history.
     */
    private suspend fun inflateSession(command: CliCommand.InflateSession) {
        val dao = db.messageDao()
        val tail = dao.tail(command.sessionId, command.n)
        if (tail.isEmpty()) {
            println("[inflate] session ${command.sessionId} has no messages — nothing to copy.")
            return
        }
        tail.forEach { row ->
            dao.insert(
                MessageEntity(
                    sessionId = command.sessionId,
                    role = row.role,
                    text = row.text,
                    // Token / pricing columns left NULL on the copies: synthetic ballast.
                )
            )
        }
        val total = dao.all(command.sessionId).size
        println("[inflate] copied ${tail.size} message(s) into session ${command.sessionId}; total now $total.")
    }

    /**
     * Cross-session summary for `ListSessions`. One row per (session, branch),
     * with message count + lifetime token/cost totals reconstructed from stored
     * ASSISTANT rows via [SessionStats], plus `compressed(...)` / `facts(...)`
     * overhead segments where present.
     */
    private suspend fun printSessionList(dao: MessageDao) {
        val sessions = dao.listSessions()
        if (sessions.isEmpty()) {
            println("(no sessions)")
            return
        }

        // Tokens + recomputed cost carried on a summary / facts row's columns.
        fun overheadOf(modelId: String?, prompt: Int?, output: Int?, thoughts: Int?, total: Int?): Pair<Int, Double> {
            val usage = Usage(
                promptTokens = prompt ?: 0,
                outputTokens = output ?: 0,
                thoughtsTokens = thoughts ?: 0,
                totalTokens = total ?: 0,
            )
            val cost = modelId?.let(PricingRegistry::lookup)?.let { PricingRegistry.cost(usage, it) } ?: 0.0
            return usage.totalTokens to cost
        }

        sessions.forEach { summary ->
            val stats = SessionStats().apply {
                seedFrom(dao.assistantMessages(summary.sessionId, summary.branchId), PricingRegistry::lookup)
            }
            val summaryRow = dao.getSummary(summary.sessionId, summary.branchId)
            val factsRow = dao.getFacts(summary.sessionId, summary.branchId)
            val (sumTok, sumCost) = summaryRow
                ?.let { overheadOf(it.modelId, it.promptTokens, it.outputTokens, it.thoughtsTokens, it.totalTokens) }
                ?: (0 to 0.0)
            val (factTok, factCost) = factsRow
                ?.let { overheadOf(it.modelId, it.promptTokens, it.outputTokens, it.thoughtsTokens, it.totalTokens) }
                ?: (0 to 0.0)
            println(
                formatSessionLine(
                    sessionId = summary.sessionId,
                    branchId = summary.branchId,
                    messageCount = summary.count,
                    totalTokens = stats.totalTokens,
                    costUsd = stats.totalCostUsd,
                    coveredCount = summaryRow?.coveredCount,
                    overheadTokens = sumTok,
                    overheadCostUsd = sumCost,
                    factsPresent = factsRow != null,
                    factsOverheadTokens = factTok,
                    factsOverheadCostUsd = factCost,
                )
            )
        }
    }

    /**
     * Shared chat / one-shot path. Both need an HTTP client + an [LlmApi]; they
     * differ only in whether they own a [HistoryStore]. The concrete [LlmApi] is
     * picked by [CliCommand.RunPrompt.modelProvider]. Chat may swap stdin for a
     * file-feed source via [CliCommand.RunChat.feedFile]; the reader's lifecycle
     * is bounded by `use { }` rather than leaked into the session.
     */
    private suspend fun runPromptCommand(parsed: CliCommand.RunPrompt) {
        val historyStore: HistoryStore? = when (parsed) {
            is CliCommand.RunChat -> {
                val sessionId = parsed.session ?: generateSessionId()
                // "Resume" = a passed name AND existing history under it. Otherwise
                // it's new — announce the id so the user can return via -session.
                val isResume = parsed.session != null &&
                    db.messageDao().all(sessionId).isNotEmpty()
                if (!isResume) {
                    System.err.println("[session] new session: $sessionId")
                }
                HistoryStore(db.messageDao(), sessionId)
            }
            is CliCommand.RunOneShot -> null
        }

        // One client for the whole session: avoids the cold-start race that
        // killed requests when the client closed too early, and keeps connections
        // warm. Engine Java (not CIO — CIO's chunked parser dies on long Gemini
        // thinking responses).
        HttpClient(Java) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }.use { client ->
            fun buildLlmApi(mp: ModelProvider): LlmApi = when (mp) {
                is ModelProvider.Gemini -> GeminiApi(httpClient = client, apiKey = mp.apiKey, model = mp.model)
                is ModelProvider.OpenRouter -> OpenRouterApi(httpClient = client, apiKey = mp.apiKey, model = mp.model)
                is ModelProvider.HuggingFace -> HuggingFaceApi(httpClient = client, apiKey = mp.apiKey, model = mp.model)
            }
            val llmApi: LlmApi = buildLlmApi(parsed.modelProvider)
            // Map the strategy kind to a concrete ContextStrategy, wiring runtime
            // deps. RunOneShot has no history, so the `as?` guard yields FullHistory.
            val chat = parsed as? CliCommand.RunChat
            val strategy: ContextStrategy = if (chat == null) {
                ContextStrategy.FullHistory
            } else when (chat.strategy) {
                ContextStrategyKind.FULL -> ContextStrategy.FullHistory
                ContextStrategyKind.WINDOW -> ContextStrategy.SlidingWindow(chat.keepLast)
                ContextStrategyKind.FACTS -> StickyFacts(chat.keepLast)
                ContextStrategyKind.SUMMARY -> ContextStrategy.Summary(
                    HistoryCompressor(keepLast = chat.keepLast, summarizeEvery = chat.summarizeEvery),
                )
            }
            // Memory layer: wired only when memoryMode is set; otherwise the wire
            // bytes are byte-identical to a no-memory run.
            val memory: MemoryProvider? = chat?.memoryMode?.let { mode ->
                MEMORY_ROOT.mkdirs()
                MemoryProvider(
                    store = MemoryStore(MEMORY_ROOT),
                    initialMode = mode,
                    initialTaskId = chat.task,
                    initialProfileName = chat.profile,
                )
            }
            // Per-stage agents: one RoutedAgent per spec, each its own LlmApi +
            // fixed profile. Empty for single-agent sessions.
            val routedAgents: List<RoutedAgent> = chat?.stageAgents.orEmpty().map { spec ->
                RoutedAgent(
                    binding = spec.binding,
                    responder = AgentResponder(
                        AgentConfig(buildLlmApi(spec.provider), parsed.toGenerationParams(), spec.profileName),
                    ),
                    profileName = spec.profileName,
                    modelId = spec.provider.modelId,
                )
            }
            // Per-stage invariant judges: one RoutedJudge per spec on its own model.
            val routedJudges: List<RoutedJudge> = chat?.judgeAgents.orEmpty().map { spec ->
                RoutedJudge(
                    binding = spec.binding,
                    checker = LlmInvariantJudge(buildLlmApi(spec.provider)),
                    modelId = spec.provider.modelId,
                )
            }
            // MCP tool server: spawn, connect, list tools once. Null when absent.
            val mcpClient: McpToolClient? = chat?.mcpServer?.let { cmd ->
                McpToolClient(cmd.split(Regex("\\s+")).filter { it.isNotEmpty() }).also { it.connect() }
            }
            val toolDefs = mcpClient?.listToolDefinitions().orEmpty()
            if (mcpClient != null) {
                System.err.println("[mcp] ${chat?.mcpServer} → tools: ${toolDefs.joinToString { it.name }}")
            }

            try {
                val feedFile = (parsed as? CliCommand.RunChat)?.feedFile
                if (feedFile != null) {
                    // File-driven feed: open the reader, hand a feed source to the
                    // session (line-by-line or fixed chunks), then REPL after EOF.
                    // `use` closes the reader when the session returns.
                    File(feedFile).bufferedReader(Charsets.UTF_8).use { reader ->
                        val feedSource: PromptSource = if (parsed.byLine) {
                            LineFilePromptSource(reader = reader, instruction = parsed.feedInstruction)
                        } else {
                            ChunkedFilePromptSource(
                                reader = reader,
                                chunkChars = parsed.chunkChars,
                                instruction = parsed.feedInstruction,
                            )
                        }
                        val stdinAfter = StdinPromptSource(
                            java.io.BufferedReader(java.io.InputStreamReader(System.`in`))
                        )
                        runSession(
                            cliArgs = parsed,
                            llmApi = llmApi,
                            historyStore = historyStore,
                            strategy = strategy,
                            memory = memory,
                            routedAgents = routedAgents,
                            routedJudges = routedJudges,
                            primary = feedSource,
                            replAfterFeed = stdinAfter,
                            toolDefs = toolDefs,
                            toolExecutor = mcpClient,
                        )
                    }
                } else {
                    // Stdin REPL — TUI when -tui and a real TTY, else plain.
                    val tuiRequested = (parsed as? CliCommand.RunChat)?.tui ?: false
                    runSession(
                        cliArgs = parsed,
                        llmApi = llmApi,
                        historyStore = historyStore,
                        strategy = strategy,
                        memory = memory,
                        routedAgents = routedAgents,
                        routedJudges = routedJudges,
                        primary = StdinPromptSource(
                            java.io.BufferedReader(java.io.InputStreamReader(System.`in`))
                        ),
                        view = pickView(tuiRequested, System.console() != null),
                        toolDefs = toolDefs,
                        toolExecutor = mcpClient,
                    )
                }
            } finally {
                mcpClient?.close()
            }
        }
    }

    /**
     * Assemble the MVI stack — [TurnEngine] + [SessionViewModel] + a renderer —
     * and run it over [primary] (with an optional feed→REPL [replAfterFeed]). The
     * 16s throttle applies only to a feed source; interactive stdin runs full speed.
     */
    private suspend fun runSession(
        cliArgs: CliCommand.RunPrompt,
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
    ) {
        val multiAgent = routedAgents.isNotEmpty()
        val schedules = (cliArgs as? CliCommand.RunChat)?.schedules.orEmpty()
        val engine = TurnEngine(
            cliArgs, llmApi, historyStore, strategy, memory, routedAgents, routedJudges, toolDefs, toolExecutor,
        )
        val commandRunner = CommandRunner(historyStore, memory, strategy)
        val viewModel = SessionViewModel(
            cliArgs, engine, commandRunner, historyStore, memory, strategy, multiAgent,
            schedulerEnabled = schedules.isNotEmpty(),
        )
        coroutineScope {
            val schedulerJobs = startScheduler(schedules, toolExecutor, viewModel)
            try {
                when (view) {
                    ViewKind.TUI -> TuiRenderer().run(viewModel, ChannelIntentSource())
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
     * Build a [SchedulerEngine] for the session's [schedules] and launch its loops on
     * `Dispatchers.IO`: a ticker that fires due tasks, and a reporter that posts an aggregated
     * report as a feed line every [SCHEDULER_REPORT_MS]. Tasks are added before the loop, filling
     * the id→[ScheduleAction] map the [CliTaskHandler] reads. Returns the jobs to cancel at
     * teardown; empty when nothing is scheduled (then the loop is byte-for-byte unchanged).
     */
    private fun CoroutineScope.startScheduler(
        schedules: List<ScheduleSpec>,
        toolExecutor: ToolExecutor?,
        vm: SessionViewModel,
    ): List<Job> {
        if (schedules.isEmpty()) return emptyList()
        val actions = mutableMapOf<String, ScheduleAction>()
        val engine = SchedulerEngine(
            InMemoryScheduleStore(),
            CliTaskHandler(actions, toolExecutor, vm::submitFromScheduler),
            now = { System.currentTimeMillis() },
        )
        val ticker = launch(Dispatchers.IO) {
            for (spec in schedules) {
                actions[engine.add(spec.label(), scheduleOf(spec)).id] = spec.toAction()
            }
            engine.runLoop(SCHEDULER_TICK_MS)
        }
        val reporter = launch(Dispatchers.IO) {
            while (isActive) {
                delay(SCHEDULER_REPORT_MS)
                vm.postNotice(engine.summary())
            }
        }
        return listOf(ticker, reporter)
    }
}

private fun scheduleOf(spec: ScheduleSpec): Schedule {
    val ms = spec.seconds * 1000L
    return if (spec.periodic) Schedule.Every(ms) else Schedule.After(ms)
}

private fun ScheduleSpec.label(): String = when (this) {
    is ScheduleSpec.Collect -> "collect $tool"
    is ScheduleSpec.Agent -> "agent: ${prompt.take(40)}"
}

private fun ScheduleSpec.toAction(): ScheduleAction = when (this) {
    is ScheduleSpec.Collect -> ScheduleAction.Collect(tool, parseToolArgs(args))
    is ScheduleSpec.Agent -> ScheduleAction.Agent(prompt)
}

private fun parseToolArgs(json: String?): JsonObject =
    json?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())

/**
 * Render one `ListSessions` row for a (session, branch). The branch is shown as
 * `session/branch` unless it's the default `main`. A `compressed(...)` segment is
 * appended when [coveredCount] is non-null (rolling summary) and a `facts(...)`
 * segment when [factsPresent]. Overhead figures are NOT folded into [totalTokens]
 * / [costUsd] — those stay the billed exchange totals; the overhead is shown
 * alongside so the price of each strategy is visible.
 */
internal fun formatSessionLine(
    sessionId: String,
    messageCount: Int,
    totalTokens: Int,
    costUsd: Double,
    branchId: String = DEFAULT_BRANCH,
    coveredCount: Int? = null,
    overheadTokens: Int = 0,
    overheadCostUsd: Double = 0.0,
    factsPresent: Boolean = false,
    factsOverheadTokens: Int = 0,
    factsOverheadCostUsd: Double = 0.0,
): String {
    val label = if (branchId == DEFAULT_BRANCH) sessionId else "$sessionId/$branchId"
    var line = "$label\t$messageCount messages" +
        "\ttotal_tokens=$totalTokens" +
        "\tcost=\$${"%.5f".format(costUsd)}"
    if (coveredCount != null) {
        line += "\tcompressed(covered=$coveredCount/$messageCount" +
            ", overhead=${overheadTokens}tok \$${"%.5f".format(overheadCostUsd)})"
    }
    if (factsPresent) {
        line += "\tfacts(overhead=${factsOverheadTokens}tok \$${"%.5f".format(factsOverheadCostUsd)})"
    }
    return line
}

/** Which renderer drives a session. */
internal enum class ViewKind { TUI, PLAIN }

/**
 * TUI only for an opted-in chat on a real TTY; feed / one-shot / non-TTY (pipe,
 * IDE, CI) all render plain. Pure so the choice is unit-testable.
 */
internal fun pickView(tui: Boolean, hasConsole: Boolean): ViewKind =
    if (tui && hasConsole) ViewKind.TUI else ViewKind.PLAIN

/**
 * Eight-char hex slice off a random UUID — readable, easy to retype, ~4 billion
 * values. Matches `^[a-zA-Z0-9_-]+$`, so it's a valid `-session` to resume.
 */
private fun generateSessionId(): String = UUID.randomUUID().toString().take(8)

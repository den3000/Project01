package ru.den.writes.code.project01.cliJvm

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import ru.den.writes.code.project01.BuildKonfig
import ru.den.writes.code.project01.cliJvm.db.AppDatabase
import ru.den.writes.code.project01.cliJvm.db.DEFAULT_BRANCH
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.db.MIGRATION_1_2
import ru.den.writes.code.project01.cliJvm.db.MIGRATION_2_3
import ru.den.writes.code.project01.cliJvm.db.MIGRATION_3_4
import ru.den.writes.code.project01.cliJvm.db.MessageDao
import ru.den.writes.code.project01.cliJvm.db.MessageEntity
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.cliJvm.memory.MemoryStore
import java.io.File
import java.util.UUID
import kotlin.system.exitProcess

/** Generous request timeout — LLM responses can take a while. */
private const val REQUEST_TIMEOUT_MS = 300_000L

/**
 * Where the session history database lives. One file for all sessions,
 * discriminated by the `session_id` column.
 */
private val DB_FILE: File = File(
    System.getProperty("user.home"),
    ".project01-cli/history.db",
)

/**
 * Root of the on-disk memory layer (Day-11). Profile, rules and task
 * notes live under this folder as markdown files — see
 * [MemoryStore] for the layout.
 */
private val MEMORY_ROOT: File = File(
    System.getProperty("user.home"),
    ".project01-cli/memory",
)

suspend fun main(args: Array<String>) {
    // Read every supported provider's key up front and hand both to the
    // parser — it picks the one that matches `-provider`, or trips a
    // MissingRequiredArgument if the chosen key is blank. Read-only
    // -sessions / -clean never touch either key.
    val geminiApiKey = BuildKonfig.GEMINI_API_KEY
    val openRouterApiKey = BuildKonfig.OPENROUTER_API_KEY
    val huggingFaceApiKey = BuildKonfig.HUGGINGFACE_API_KEY

    val initialArgs = try {
        CliArgs.from(
            args,
            geminiApiKey = geminiApiKey,
            openRouterApiKey = openRouterApiKey,
            huggingFaceApiKey = huggingFaceApiKey,
        )
    } catch (e: CliArgsException) {
        System.err.println(e.message)
        if (e is CliArgsException.MissingRequiredArgument) {
            System.err.println(CliArgs.USAGE)
        }
        exitProcess(1)
    }

    DB_FILE.parentFile.mkdirs()
    val db = Room.databaseBuilder<AppDatabase>(name = DB_FILE.absolutePath)
        .setDriver(BundledSQLiteDriver())
        // WAL lets parallel processes open the same file safely: one
        // writer + many readers at any moment, no blocking. With our
        // session_id discriminator, distinct -session values touch
        // disjoint rows and don't fight for the writer lock either.
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        // v1→v2: Day-8 token columns; v2→v3: Day-9 `summaries` table;
        // v3→v4: Day-10 branch_id + `facts` table (see MIGRATION_1_2 /
        // MIGRATION_2_3 / MIGRATION_3_4). Without these, opening an older
        // DB would throw IllegalStateException at startup.
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .build()

    try {
        when (initialArgs) {
            is CliArgs.ListSessions -> printSessionList(db.messageDao())
            is CliArgs.Clean -> {
                val before = db.messageDao().count()
                db.messageDao().clearAll()
                // Wipe the compression summaries + sticky facts too — otherwise
                // an orphan row would resurrect on a reused session id.
                db.messageDao().clearAllSummaries()
                db.messageDao().clearAllFacts()
                println("Cleared $before messages across all sessions (and any saved summaries / facts).")
            }
            is CliArgs.Inflate -> inflateSession(db, initialArgs)
            is CliArgs.Memory -> handleMemoryCommand(initialArgs.action)
            is CliArgs.PromptCommand -> runPromptCommand(db, initialArgs)
        }
    } finally {
        db.close()
    }
}

/**
 * Run a `-memory <subcommand>` invocation against the on-disk memory
 * root. Pure disk operation — no LLM, no session, no DB. Prints a
 * short status line to stdout (errors to stderr) and returns.
 */
private fun handleMemoryCommand(action: CliArgs.MemoryAction) {
    MEMORY_ROOT.mkdirs()
    val store = MemoryStore(MEMORY_ROOT)
    when (action) {
        is CliArgs.MemoryAction.Show -> {
            // Use a temporary provider in PREAMBLE mode for describe() —
            // no task is active when invoked from the CLI; this prints
            // the dormant snapshot of every layer.
            println(MemoryProvider(store, initialTaskId = null).describe())
        }
        is CliArgs.MemoryAction.SetProfile -> {
            store.saveProfile(action.text)
            println("[memory] profile saved (${action.text.length} char(s))")
        }
        is CliArgs.MemoryAction.AddRule -> {
            val rule = store.addRule(action.text)
            println("[memory] rule ${rule.id} added")
        }
        is CliArgs.MemoryAction.RemoveRule -> {
            val removed = store.removeRule(action.id)
            if (removed) println("[memory] rule ${action.id} removed")
            else System.err.println("[memory] no rule with id '${action.id}'")
        }
        is CliArgs.MemoryAction.SetTask -> {
            // Touch-create so subsequent `-memory show` (and the next
            // chat with `-task <id>`) sees an empty task file rather
            // than nothing on disk.
            if (store.loadTask(action.taskId) == null) {
                store.saveTask(ru.den.writes.code.project01.cliJvm.memory.TaskNotes(taskId = action.taskId))
            }
            println("[memory] task '${action.taskId}' ready under ${File(MEMORY_ROOT, MemoryStore.TASKS_DIR).absolutePath}/${action.taskId}.md")
        }
    }
}

/**
 * Duplicates the last N rows of the given session in-place. No LLM
 * call, no network, no token spend — pure DB ALTER. Copies carry just
 * the original `text` and `role`; `model_id` and all token counts are
 * cleared so [SessionStats] doesn't double-count usage that was
 * already billed for the original rows. The next real LLM turn sees
 * the inflated history and Gemini will report a correspondingly
 * larger `promptTokens` — which is the whole point of this op.
 */
private suspend fun inflateSession(db: AppDatabase, parsed: CliArgs.Inflate) {
    val dao = db.messageDao()
    val tail = dao.tail(parsed.sessionId, parsed.n)
    if (tail.isEmpty()) {
        println("[inflate] session ${parsed.sessionId} has no messages — nothing to copy.")
        return
    }
    tail.forEach { row ->
        dao.insert(
            MessageEntity(
                sessionId = parsed.sessionId,
                role = row.role,
                text = row.text,
                // Token / pricing columns left NULL on the copies: this
                // is synthetic ballast, not a real API exchange.
            )
        )
    }
    val total = dao.all(parsed.sessionId).size
    println(
        "[inflate] copied ${tail.size} message(s) into session ${parsed.sessionId}; " +
            "total now $total."
    )
}

/**
 * Cross-session summary printed by `-sessions`. One row per (session, branch),
 * showing the message count + lifetime token/cost totals reconstructed from the
 * stored ASSISTANT rows via [SessionStats]. A `compressed(...)` segment is
 * appended when the branch has a rolling summary, and a `facts(...)` segment
 * when it has sticky facts — each reporting its cumulative extraction overhead.
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
 * Render one `-sessions` row for a (session, branch). The branch is shown as
 * `session/branch` unless it's the default `main`. A `compressed(...)` segment
 * is appended when [coveredCount] is non-null (the branch has a rolling
 * summary) and a `facts(...)` segment when [factsPresent] (it has sticky
 * facts). The overhead figures report cumulative summarization / extraction
 * spend, deliberately NOT folded into [totalTokens] / [costUsd] — those stay
 * the billed exchange totals so the column keeps its meaning; the overhead is
 * shown alongside so the price of each strategy is visible.
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

/**
 * Shared Chat / OneShot path. Both need an HTTP client + an [LlmApi];
 * they differ only in whether they own a [HistoryStore]. The concrete
 * [LlmApi] is picked by [CliArgs.PromptCommand.modelProvider].
 *
 * Chat may additionally swap stdin for a file-feed source via
 * [CliArgs.Chat.feedFile]; the file's reader is opened here so its
 * lifecycle is bounded by `use { }` rather than leaked into Agent.
 */
private suspend fun runPromptCommand(db: AppDatabase, parsed: CliArgs.PromptCommand) {
    val historyStore: HistoryStore? = when (parsed) {
        is CliArgs.Chat -> {
            val sessionId = parsed.session ?: generateSessionId()
            // "Resume" = the user passed a name AND there's already history
            // under it. Otherwise it's a new session — either auto-generated
            // id or a freshly named one — so we announce the id so the user
            // can come back to it later via -session.
            val isResume = parsed.session != null &&
                db.messageDao().all(sessionId).isNotEmpty()
            if (!isResume) {
                System.err.println("[session] new session: $sessionId")
            }
            HistoryStore(db.messageDao(), sessionId)
        }
        is CliArgs.OneShot -> null
    }

    // One client for the whole session: avoids the cold-start race that
    // sometimes killed requests when the client was closed too early, and
    // keeps connections warm between prompts.
    //
    // Engine: Java (JDK 11+ HttpClient). Picked over CIO because CIO's
    // chunked-encoding parser intermittently dies on long Gemini responses
    // ("Invalid chunk: content block of size N ended unexpectedly"), most
    // visibly with thinking-capable models like gemini-3.5-flash.
    HttpClient(Java) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                // Drop null fields from the request body so we send a clean
                // payload only with the knobs the user set.
                explicitNulls = false
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }.use { client ->
        val llmApi: LlmApi = when (val mp = parsed.modelProvider) {
            is ModelProvider.Gemini -> GeminiApi(
                httpClient = client,
                apiKey = mp.apiKey,
                model = mp.model,
            )
            is ModelProvider.OpenRouter -> OpenRouterApi(
                httpClient = client,
                apiKey = mp.apiKey,
                model = mp.model,
            )
            is ModelProvider.HuggingFace -> HuggingFaceApi(
                httpClient = client,
                apiKey = mp.apiKey,
                model = mp.model,
            )
        }
        // Map the parsed strategy kind to a concrete ContextStrategy, wiring
        // in runtime deps (the compressor, the window size). OneShot has no
        // history, so the `as? Chat` guard yields null → FullHistory.
        val chat = parsed as? CliArgs.Chat
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
        // Day-11 memory: only wired in when the user passed -memory-mode.
        // Without it, Agent receives a null MemoryProvider and the wire
        // bytes are byte-identical to a pre-Day-11 run.
        val memory: MemoryProvider? = chat?.memoryMode?.let { mode ->
            MEMORY_ROOT.mkdirs()
            MemoryProvider(
                store = MemoryStore(MEMORY_ROOT),
                initialMode = mode,
                initialTaskId = chat.task,
            )
        }
        val feedFile = (parsed as? CliArgs.Chat)?.feedFile
        if (feedFile != null) {
            // File-driven feed mode: open the reader and hand a feed source
            // to Agent — line-by-line (-byLine) or fixed-size character
            // chunks. After the file is fully read, Agent transitions to
            // `replAfterFeed` so the user can keep chatting until they type
            // /exit. `use` closes the reader when Agent.run() returns.
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
                Agent(
                    cliArgs = parsed,
                    llmApi = llmApi,
                    historyStore = historyStore,
                    promptSource = feedSource,
                    replAfterFeed = stdinAfter,
                    strategy = strategy,
                    memory = memory,
                ).run()
            }
        } else {
            // Stdin REPL: Agent's default StdinPromptSource takes
            // System.in directly.
            Agent(
                cliArgs = parsed,
                llmApi = llmApi,
                historyStore = historyStore,
                strategy = strategy,
                memory = memory,
            ).run()
        }
    }
}

/**
 * Eight-char hex slice off a random UUID — readable on screen, easy to
 * retype, ~4 billion possible values (collision chance vanishing for
 * personal-use scale). Format matches `^[a-zA-Z0-9_-]+$`, so it's a
 * valid `-session` argument when the user wants to resume.
 */
private fun generateSessionId(): String = UUID.randomUUID().toString().take(8)

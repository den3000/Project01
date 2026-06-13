package ru.den.writes.code.project01.cliJvm

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import jdk.internal.agent.Agent
import kotlinx.serialization.json.Json
import ru.den.writes.code.project01.BuildKonfig
import ru.den.writes.code.project01.cliJvm.db.AppDatabase
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.db.MIGRATION_1_2
import ru.den.writes.code.project01.cliJvm.db.MessageDao
import java.io.File
import java.util.UUID
import kotlin.system.exitProcess

/** Generous request timeout — LLM responses can take a while. */
private const val REQUEST_TIMEOUT_MS = 120_000L

/**
 * Where the session history database lives. One file for all sessions,
 * discriminated by the `session_id` column.
 */
private val DB_FILE: File = File(
    System.getProperty("user.home"),
    ".project01-cli/history.db",
)

suspend fun main(args: Array<String>) {
    // Read every supported provider's key up front and hand both to the
    // parser — it picks the one that matches `-provider`, or trips a
    // MissingRequiredArgument if the chosen key is blank. Read-only
    // -sessions / -clean never touch either key.
    val geminiApiKey = BuildKonfig.GEMINI_API_KEY
    val openRouterApiKey = BuildKonfig.OPENROUTER_API_KEY

    val initialArgs = try {
        CliArgs.from(args, geminiApiKey = geminiApiKey, openRouterApiKey = openRouterApiKey)
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
        // v1→v2: hand-written ALTER TABLE for the Day-8 token columns
        // (see MIGRATION_1_2). Without this, opening an old v1 DB
        // would throw IllegalStateException at startup.
        .addMigrations(MIGRATION_1_2)
        .build()

    try {
        when (initialArgs) {
            is CliArgs.ListSessions -> printSessionList(db.messageDao())
            is CliArgs.Clean -> {
                val before = db.messageDao().count()
                db.messageDao().clearAll()
                println("Cleared $before messages across all sessions.")
            }
            is CliArgs.PromptCommand -> runPromptCommand(db, initialArgs)
        }
    } finally {
        db.close()
    }
}

/**
 * Cross-session summary printed by `-sessions`. For each known session,
 * shows the message count plus the lifetime token / cost totals
 * reconstructed from the stored ASSISTANT rows via [SessionStats].
 */
private suspend fun printSessionList(dao: MessageDao) {
    val sessions = dao.listSessions()
    if (sessions.isEmpty()) {
        println("(no sessions)")
        return
    }
    sessions.forEach { summary ->
        val stats = SessionStats().apply {
            seedFrom(dao.assistantMessages(summary.sessionId), PricingRegistry::lookup)
        }
        println(
            "${summary.sessionId}\t${summary.count} messages" +
                "\ttotal_tokens=${stats.totalTokens}" +
                "\tcost=\$${"%.5f".format(stats.totalCostUsd)}"
        )
    }
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
        }
        val feedFile = (parsed as? CliArgs.Chat)?.feedFile
        if (feedFile != null) {
            // File-driven feed mode: open the reader, hand a
            // ChunkedFilePromptSource to Agent. After the file is fully
            // read, Agent transitions to `replAfterFeed` so the user can
            // keep chatting until they type /exit. `use` closes the
            // reader when Agent.run() returns (normally or via error).
            File(feedFile).bufferedReader(Charsets.UTF_8).use { reader ->
                val feedSource = ChunkedFilePromptSource(
                    reader = reader,
                    chunkChars = parsed.chunkChars,
                    instruction = parsed.feedInstruction,
                )
                val stdinAfter = StdinPromptSource(
                    java.io.BufferedReader(java.io.InputStreamReader(System.`in`))
                )
                Agent(
                    cliArgs = parsed,
                    llmApi = llmApi,
                    historyStore = historyStore,
                    promptSource = feedSource,
                    replAfterFeed = stdinAfter,
                ).run()
            }
        } else {
            // Stdin REPL: Agent's default StdinPromptSource takes
            // System.in directly.
            Agent(cliArgs = parsed, llmApi = llmApi, historyStore = historyStore).run()
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

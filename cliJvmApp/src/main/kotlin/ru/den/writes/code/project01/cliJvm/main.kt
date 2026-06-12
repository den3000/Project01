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
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import java.io.File
import java.util.UUID
import kotlin.system.exitProcess

private const val KEY_TOKEN = "GEMINI_API_KEY"

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
    val initialArgs = try {
        CliArgs.from(args)
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
        .build()

    try {
        if (initialArgs.listSessions) {
            val sessions = db.messageDao().listSessions()
            if (sessions.isEmpty()) {
                println("(no sessions)")
            } else {
                sessions.forEach { println("${it.sessionId}\t${it.count} messages") }
            }
            return
        }

        // Run mode below. We only need the API key here — list mode never
        // talks to the LLM.
        val apiKey = BuildKonfig.GEMINI_API_KEY.takeIf { it.isNotBlank() } ?: run {
            System.err.println(
                """$KEY_TOKEN not found.
                  |Add "$KEY_TOKEN=<key>" to local.properties at the project root,
                  |or set $KEY_TOKEN as an environment variable.""".trimMargin()
            )
            exitProcess(1)
        }

        val sessionId = initialArgs.session ?: generateSessionId()
        // "Resume" = the user passed a name AND there's already history under
        // it. Otherwise it's a new session — either auto-generated id or a
        // freshly named one — so we announce the id so the user can come back
        // to it later via -session.
        val isResume = initialArgs.session != null &&
            db.messageDao().all(sessionId).isNotEmpty()
        if (!isResume) {
            System.err.println("[session] new session: $sessionId")
        }
        val historyStore = HistoryStore(db.messageDao(), sessionId)

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
                    // `generationConfig` only with the knobs the user set.
                    explicitNulls = false
                })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }.use { client ->
            val llmApi = GeminiApi(httpClient = client, apiKey = apiKey, model = initialArgs.model)
            Agent(cliArgs = initialArgs, llmApi = llmApi, historyStore = historyStore).runRepl()
        }
    } finally {
        db.close()
    }
}

/**
 * Eight-char hex slice off a random UUID — readable on screen, easy to
 * retype, ~4 billion possible values (collision chance vanishing for
 * personal-use scale). Format matches `^[a-zA-Z0-9_-]+$`, so it's a
 * valid `-session` argument when the user wants to resume.
 */
private fun generateSessionId(): String = UUID.randomUUID().toString().take(8)

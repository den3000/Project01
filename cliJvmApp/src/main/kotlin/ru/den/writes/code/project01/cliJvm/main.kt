package ru.den.writes.code.project01.cliJvm

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ru.den.writes.code.project01.BuildKonfig
import ru.den.writes.code.project01.cliJvm.command.ApiKeys
import ru.den.writes.code.project01.cliJvm.command.CliArgsCommandParser
import ru.den.writes.code.project01.cliJvm.command.USAGE
import ru.den.writes.code.project01.cliJvm.db.AppDatabase
import ru.den.writes.code.project01.cliJvm.db.MIGRATION_1_2
import ru.den.writes.code.project01.cliJvm.db.MIGRATION_2_3
import ru.den.writes.code.project01.cliJvm.db.MIGRATION_3_4
import java.io.File
import kotlin.system.exitProcess

/**
 * Where the session history database lives. One file for all sessions,
 * discriminated by the `session_id` column.
 */
private val DB_FILE: File = File(
    System.getProperty("user.home"),
    ".project01-cli/history.db",
)

/**
 * Bootstrap: read provider keys, parse args into a [StartCommand] (the unified
 * cliargs front), open the database, and run the command. Admin commands finish
 * inside [StartExecutor.execute] and return null; a [StartCommand.SessionInitialState]
 * comes back and this launches the session over one HTTP client. Stays thin
 * (parse → execute → maybe run a session); the DB spans both, closed in `finally`.
 */
suspend fun main(args: Array<String>) {
    // Read every supported provider's key up front; the parser picks the one
    // matching -provider, or trips MissingRequiredArgument if the chosen key is
    // blank. Read-only commands (sessions / clean) never touch a key.
    val keys = ApiKeys(
        gemini = BuildKonfig.GEMINI_API_KEY,
        openRouter = BuildKonfig.OPENROUTER_API_KEY,
        huggingFace = BuildKonfig.HUGGINGFACE_API_KEY,
    )

    val command = try {
        CliArgsCommandParser(keys).parse(args)
    } catch (e: CliArgsException) {
        System.err.println(e.message)
        if (e is CliArgsException.MissingRequiredArgument) {
            System.err.println(USAGE)
        }
        exitProcess(1)
    }

    val db = database()
    try {
        val initialState = StartExecutor(db).execute(command)
        if (initialState != null) {
            buildHttpClient().use { client -> runSession(client, db, initialState) }
        }
    } finally {
        db.close()
    }
}

/** Open the session history database, creating its parent dir and applying migrations. */
private fun database(): AppDatabase {
    DB_FILE.parentFile.mkdirs()
    return Room.databaseBuilder<AppDatabase>(name = DB_FILE.absolutePath)
        .setDriver(BundledSQLiteDriver())
        // WAL lets parallel processes open the same file safely: one writer +
        // many readers at any moment, no blocking. With our session_id
        // discriminator, distinct -session values touch disjoint rows.
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        // v1→v2: token columns; v2→v3: `summaries`; v3→v4: branch_id + `facts`
        // (MIGRATION_1_2 / _2_3 / _3_4). Without these, opening an older DB throws.
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .build()
}

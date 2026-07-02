package ru.den.writes.code.agenticHub.cliJvm

import ru.den.writes.code.agenticHub.BuildKonfig
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArgsParser
import ru.den.writes.code.agenticHub.cliJvm.cliargs.ParseError
import ru.den.writes.code.agenticHub.cliJvm.commandMappers.CliArgToSessionCommandMapper
import ru.den.writes.code.agenticHub.cliJvm.commandMappers.CliArgsToStartCommandMapper
import ru.den.writes.code.agenticHub.cliJvm.commandMappers.ParsedStartCommand
import ru.den.writes.code.agenticHub.cliJvm.cliargs.USAGE
import ru.den.writes.code.agenticHub.platform.database.buildDatabase
import kotlin.system.exitProcess

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

    // One parser at startup feeds both mappers: the startup `-flag` front
    // (args → StartCommand) and the in-session `/`-command front (line →
    // SessionCommand), threaded down to the session so nothing reaches for a global.
    val parser = CliArgsParser()
    val startMapper = CliArgsToStartCommandMapper(parser, ModelProviderFactory(keys))
    val sessionMapper = CliArgToSessionCommandMapper(parser)

    val command = when (val outcome = startMapper.parse(args)) {
        is ParsedStartCommand.Ok -> outcome.command
        is ParsedStartCommand.Err -> {
            System.err.println(outcome.error.message)
            if (outcome.error is ParseError.MissingArg) {
                System.err.println(USAGE)
            }
            exitProcess(1)
        }
    }

    val db = buildDatabase()
    try {
        val initialState = StartExecutor(db).execute(command)
        if (initialState != null) {
            buildHttpClient().use { client -> runSession(client, db, initialState, sessionMapper) }
        }
    } finally {
        db.close()
    }
}

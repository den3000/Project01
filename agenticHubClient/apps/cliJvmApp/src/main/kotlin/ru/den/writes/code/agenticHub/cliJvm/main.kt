package ru.den.writes.code.agenticHub.cliJvm

import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import ru.den.writes.code.agenticHub.features.lifecycle.start.StartExecutor
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArgsParser
import ru.den.writes.code.agenticHub.cliJvm.cliargs.ParseError
import ru.den.writes.code.agenticHub.cliJvm.commandMappers.CliArgToSessionCommandMapper
import ru.den.writes.code.agenticHub.cliJvm.commandMappers.CliArgsToStartCommandMapper
import ru.den.writes.code.agenticHub.cliJvm.commandMappers.ParsedStartCommand
import ru.den.writes.code.agenticHub.cliJvm.cliargs.USAGE
import ru.den.writes.code.agenticHub.cliJvm.di.appModule
import ru.den.writes.code.agenticHub.features.agent.di.agentModule
import ru.den.writes.code.agenticHub.features.lifecycle.session.di.sessionModule
import ru.den.writes.code.agenticHub.features.lifecycle.start.di.startModule
import ru.den.writes.code.agenticHub.features.llm.di.llmModule
import ru.den.writes.code.agenticHub.features.mcpclient.di.mcpClientModule
import ru.den.writes.code.agenticHub.features.memory.di.memoryModule
import ru.den.writes.code.agenticHub.features.rag.di.ragModule
import ru.den.writes.code.agenticHub.platform.database.di.databaseModule
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemModule
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import kotlin.system.exitProcess

/**
 * Bootstrap: start the Koin graph, parse args into a [StartCommand] (the unified
 * cliargs front), and run the command. Admin commands finish inside
 * [StartExecutor.execute] and return null; a [StartCommand.SessionInitialState]
 * comes back and this launches the session over the graph's HTTP client. Stays
 * thin (parse → execute → maybe run a session); `stopKoin()` in `finally` closes
 * the HTTP client and the database (both via their `onClose`).
 */
suspend fun main(args: Array<String>) {
    val koin = startKoin {
        modules(
            appModule,
            networkModule,
            fileSystemModule,
            databaseModule,
            llmModule,
            memoryModule,
            agentModule,
            mcpClientModule,
            ragModule,
            startModule,
            sessionModule,
        )
    }.koin

    try {
        // One parser at startup feeds both mappers: the startup `-flag` front
        // (args → StartCommand) and the in-session `/`-command front (line →
        // SessionCommand), threaded down to the session so nothing reaches for a global.
        val parser = koin.get<CliArgsParser>()
        val startMapper = CliArgsToStartCommandMapper(parser, koin.get<ModelProviderFactory>())
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

        val initialState = koin.get<StartExecutor>().execute(command)
        if (initialState != null) {
            runSession(koin, initialState, sessionMapper)
        }
    } finally {
        stopKoin()
    }
}

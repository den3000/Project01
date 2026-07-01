package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.cliJvm.command.StartCommand
import ru.den.writes.code.project01.cliJvm.db.AppDatabase
import java.io.File

/**
 * Root of the on-disk memory layer. Profile, rules and task notes live under
 * this folder as markdown files. Shared by the admin memory ops (via [AdminOps])
 * and the session's `memoryProvider` accessor.
 */
internal val MEMORY_ROOT: File = File(
    System.getProperty("user.home"),
    ".project01-cli/memory",
)

/**
 * Runs a parsed [StartCommand] against the runtime — the "how" to the parser's
 * "what". Admin commands (list / clean / inflate / memory) run through [AdminOps]
 * (features:viewModel) and their notices are printed here on the tagged stream;
 * a [StartCommand.SessionInitialState] is returned unrun for `main` to launch.
 * Owns only the [db].
 */
internal class StartExecutor(private val db: AppDatabase) {
    private val ops = AdminOps(db)

    suspend fun execute(command: StartCommand): StartCommand.SessionInitialState? = when (command) {
        is StartCommand.ListSessions -> { ops.listSessions().print(); null }
        is StartCommand.CleanHistory -> { ops.cleanHistory().print(); null }
        is StartCommand.CleanSession -> { ops.cleanSession(command.sessionId).print(); null }
        is StartCommand.InflateSession -> { ops.inflateSession(command).print(); null }
        is StartCommand.MemoryOp -> { ops.handleMemoryCommand(command.action, MEMORY_ROOT.absolutePath).print(); null }
        is StartCommand.SessionInitialState -> command
    }
}

/** Print each admin notice on its tagged stream, preserving the CLI's stdout/stderr split. */
private fun List<AdminNotice>.print() = forEach {
    when (it.stream) {
        OutputStream.STDOUT -> println(it.text)
        OutputStream.STDERR -> System.err.println(it.text)
    }
}

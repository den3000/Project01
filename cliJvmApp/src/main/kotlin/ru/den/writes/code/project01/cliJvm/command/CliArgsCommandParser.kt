package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.cliargs.CliArgsParser

/**
 * The runtime arg front: parse args with the cliargs grammar and map the
 * controls straight onto a domain [CliCommand]. Parse errors are surfaced as
 * [ru.den.writes.code.project01.cliJvm.CliArgsException] (the type `main` prints).
 */
internal class CliArgsCommandParser(private val keys: ApiKeys) : CommandParser {
    private val parser = CliArgsParser()

    override fun parse(args: Array<String>): CliCommand {
        val batch = parser.parseArgv(args.toList())
        batch.errors.firstOrNull()?.let { throw it.toCliArgsException() }
        return ControlsToCommand(keys).map(batch.controls)
    }
}

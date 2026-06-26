package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsParser

/**
 * The runtime arg front: parse args with the clicontrols grammar and map the
 * controls straight onto a domain [CliCommand]. Parse errors are surfaced as
 * [ru.den.writes.code.project01.cliJvm.CliArgsException] (the type `main` prints).
 */
internal class CliControlsCommandParser(private val keys: ApiKeys) : CommandParser {
    private val parser = CliControlsParser()

    override fun parse(args: Array<String>): CliCommand {
        val batch = parser.parseArgv(args.toList())
        batch.errors.firstOrNull()?.let { throw it.toCliArgsException() }
        return ControlsToCommand(keys).map(batch.controls)
    }
}

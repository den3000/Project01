package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsParser

/**
 * The redesigned front: parse args with the CliControls grammar and map the
 * controls straight onto a domain [CliCommand] (no [ru.den.writes.code.project01.cliJvm.CliArgs]
 * intermediary). Parse errors are bridged to the legacy exception type
 * [ru.den.writes.code.project01.cliJvm.CliArgsException] so the caller is
 * agnostic to which front parsed. Test-only for now — `main` runs on
 * [LegacyCommandParser].
 */
internal class CliControlsCommandParser(private val keys: ApiKeys) : CommandParser {
    private val parser = CliControlsParser()

    override fun parse(args: Array<String>): CliCommand {
        val batch = parser.parseArgv(args.toList())
        batch.errors.firstOrNull()?.let { throw it.toCliArgsException() }
        return ControlsToCommand(keys).map(batch.controls)
    }
}

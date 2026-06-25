package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.CliArgs

/**
 * The default, proven front: parse with the legacy [CliArgs.from], then map its
 * result onto the domain [CliCommand]. This is the "safety" path the app runs on
 * while the CliControls front matures.
 */
internal class LegacyCommandParser(private val keys: ApiKeys) : CommandParser {
    override fun parse(args: Array<String>): CliCommand =
        CliArgs.from(args, keys.gemini, keys.openRouter, keys.huggingFace).toCliCommand()
}

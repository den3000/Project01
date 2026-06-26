package ru.den.writes.code.project01.cliJvm.command

/** Provider API keys, injected into a parser at construction. */
internal data class ApiKeys(
    val gemini: String = "",
    val openRouter: String = "",
    val huggingFace: String = "",
)

/**
 * Turns raw program args into a domain [CliCommand]. The single implementation,
 * [CliControlsCommandParser], parses against the shared clicontrols grammar and
 * maps the controls straight onto a command; it throws
 * [ru.den.writes.code.project01.cliJvm.CliArgsException] on bad input.
 */
internal interface CommandParser {
    /** @throws ru.den.writes.code.project01.cliJvm.CliArgsException on invalid input. */
    fun parse(args: Array<String>): CliCommand
}

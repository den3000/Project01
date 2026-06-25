package ru.den.writes.code.project01.cliJvm.command

/** Provider API keys, injected into a parser at construction. */
internal data class ApiKeys(
    val gemini: String = "",
    val openRouter: String = "",
    val huggingFace: String = "",
)

/**
 * Turns raw program args into a domain [CliCommand]. One seam, two impls:
 * [LegacyCommandParser] (via the proven `CliArgs.from` + a mapping) and the
 * CliControls-backed parser (produces commands directly). Both throw
 * [ru.den.writes.code.project01.cliJvm.CliArgsException] on bad input, so the
 * caller is agnostic to which front parsed.
 */
internal interface CommandParser {
    /** @throws ru.den.writes.code.project01.cliJvm.CliArgsException on invalid input. */
    fun parse(args: Array<String>): CliCommand
}

package ru.den.writes.code.project01.cliJvm

/**
 * Errors raised while turning program args into a command. The cliargs front
 * maps its typed [ru.den.writes.code.project01.cliJvm.cliargs.ParseError]s onto
 * these (see `command/ParseErrorMapping`). Each subclass carries the data the
 * caller needs to render a meaningful message; `main` prints it (plus
 * [ru.den.writes.code.project01.cliJvm.command.USAGE] on a missing required arg).
 */
internal sealed class CliArgsException(message: String) : RuntimeException(message) {
    /** Thrown when a required flag is missing or its value is blank. */
    class MissingRequiredArgument(val argName: String, detail: String? = null) :
        CliArgsException("Missing required argument $argName${detail?.let { ": $it" } ?: "."}")

    /** Thrown when a typed flag (e.g. an integer) cannot be parsed. */
    class InvalidArgumentValue(
        val argName: String,
        val rawValue: String,
        val expectedType: String,
    ) : CliArgsException("$argName must be $expectedType, got \"$rawValue\".")

    /** Thrown when a flag's value contains more sub-values than the cap allows. */
    class TooManyValues(
        val argName: String,
        val count: Int,
        val maxAllowed: Int,
    ) : CliArgsException("$argName accepts up to $maxAllowed values, got $count.")
}

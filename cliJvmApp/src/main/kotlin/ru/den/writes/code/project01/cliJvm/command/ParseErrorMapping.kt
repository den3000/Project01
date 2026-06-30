package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg
import ru.den.writes.code.project01.cliJvm.cliargs.ParseError

/**
 * Bridge a CliArgs [ParseError] onto the legacy [CliArgsException] hierarchy,
 * so the CliArgs front throws the same exception type `main` already catches
 * (prints message + USAGE). Missing-something errors map to
 * [CliArgsException.MissingRequiredArgument]; everything else to
 * [CliArgsException.InvalidArgumentValue], preserving the offending arg / raw /
 * reason. (`TooManyValues` has no [ParseError] source — the catalog has no count
 * cap; the mapper raises it itself when collapsing stop-sequences.)
 */
internal fun ParseError.toCliArgsException(): CliArgsException = when (this) {
    is ParseError.MissingValue ->
        CliArgsException.MissingRequiredArgument(flag(arg), "needs a value")
    is ParseError.Requires ->
        CliArgsException.MissingRequiredArgument(flag(missing), "required by ${flag(arg)}")
    is ParseError.Empty ->
        CliArgsException.MissingRequiredArgument("(input)", "no control given")
    is ParseError.BadValue ->
        CliArgsException.InvalidArgumentValue(flag(arg), raw, reason)
    is ParseError.BadPrefix ->
        CliArgsException.InvalidArgumentValue(token, token, "a '${surface.prefix}' control")
    is ParseError.UnknownControl ->
        CliArgsException.InvalidArgumentValue(token, token, "a known control")
    is ParseError.WrongSurface ->
        CliArgsException.InvalidArgumentValue(token, surface.name.lowercase(), "valid on this surface")
    is ParseError.ValueNotAllowedHere ->
        CliArgsException.InvalidArgumentValue(flag(arg), surface.name.lowercase(), "no value on this surface")
    is ParseError.WrongParentValue ->
        CliArgsException.InvalidArgumentValue(flag(arg), parentValue ?: "(none)", "parent in ${allowed.joinToString("/")}")
    is ParseError.UnexpectedToken ->
        CliArgsException.InvalidArgumentValue(token, token, "not expected here")
    is ParseError.Conflicts ->
        CliArgsException.InvalidArgumentValue(flag(arg), flag(with), "not combinable with ${flag(with)}")
}

/** Present a catalog arg the way the legacy errors name flags: `-<title>`. */
private fun flag(arg: CliArg): String = "-${arg.title}"

package ru.den.writes.code.project01.cliJvm.cliargs

/**
 * The result layer: what the user actually invoked. A control plus its parsed
 * [value] and its [subs] — a LIST, not a single sub, so it covers both shapes:
 *  - nested chain:  `profile work style "terse"` → PROFILE(value=work, subs=[STYLE(value=terse)])
 *  - flat bag:      `agent main provider gemini model x` → AGENT(value=main,
 *                     subs=[PROVIDER(gemini), MODEL(x)])
 * A thin downstream mapper would turn this into a typed domain command; the
 * grammar (descriptors) stays separate from the domain.
 */
data class ParsedArg(
    val spec: ArgSpec,
    val value: String? = null,
    val subs: List<ParsedArg> = emptyList(),
) {
    val arg: CliArg get() = spec.arg

    /** The first sub invoked under [arg], or null. */
    fun sub(arg: CliArg): ParsedArg? = subs.firstOrNull { it.arg == arg }
}

/** Outcome of parsing one control. */
internal sealed interface ParseResult {
    data class Ok(val control: ParsedArg) : ParseResult
    data class Err(val error: ParseError) : ParseResult
}

/** Outcome of parsing a whole startup argv: the controls that parsed + every error found. */
data class BatchResult(val controls: List<ParsedArg>, val errors: List<ParseError>) {
    val isValid: Boolean get() = errors.isEmpty()
}

/** Why parsing failed — typed, so callers (and tests) match on the cause, not a string. */
sealed interface ParseError {
    val message: String

    data object Empty : ParseError {
        override val message = "no control given"
    }

    data class BadPrefix(val token: String, val surface: Surface) : ParseError {
        override val message = "'$token' is not a '${surface.prefix}' ${surface.name.lowercase()}"
    }

    data class UnknownControl(val token: String) : ParseError {
        override val message = "unknown control '$token'"
    }

    data class WrongSurface(val token: String, val surface: Surface) : ParseError {
        override val message = "'$token' can't be used as a ${surface.name.lowercase()}"
    }

    data class MissingValue(val arg: CliArg) : ParseError {
        override val message = "'${arg.title}' needs a value"
    }

    data class BadValue(val arg: CliArg, val raw: String, val reason: String) : ParseError {
        override val message = "'${arg.title}' got '$raw' — expected $reason"
    }

    data class ValueNotAllowedHere(val arg: CliArg, val surface: Surface) : ParseError {
        override val message = "'${arg.title}' takes no value as a ${surface.name.lowercase()}"
    }

    data class WrongParentValue(
        val arg: CliArg,
        val parent: CliArg,
        val parentValue: String?,
        val allowed: Set<String>,
    ) : ParseError {
        override val message = "'${arg.title}' is only valid when '${parent.title}' is ${allowed.joinToString("/")}"
    }

    data class UnexpectedToken(val token: String) : ParseError {
        override val message = "unexpected '$token'"
    }

    data class Requires(val arg: CliArg, val missing: CliArg) : ParseError {
        override val message = "'${arg.title}' requires '${missing.title}'"
    }

    data class Conflicts(val arg: CliArg, val with: CliArg) : ParseError {
        override val message = "'${arg.title}' can't be combined with '${with.title}'"
    }
}

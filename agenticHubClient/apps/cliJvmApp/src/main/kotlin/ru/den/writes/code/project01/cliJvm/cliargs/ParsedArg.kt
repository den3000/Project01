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

    /**
     * The "something required is missing" family — `main` prints USAGE for these
     * (an incomplete invocation), unlike the invalid-value family.
     */
    sealed interface MissingArg : ParseError

    data object Empty : MissingArg {
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

    data class MissingValue(val arg: CliArg) : MissingArg {
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

    data class Requires(val arg: CliArg, val missing: CliArg) : MissingArg {
        override val message = "'${arg.title}' requires '${missing.title}'"
    }

    data class Conflicts(val arg: CliArg, val with: CliArg) : ParseError {
        override val message = "'${arg.title}' can't be combined with '${with.title}'"
    }

    // --- Post-parse semantic errors -------------------------------------
    // Raised by the mapper / model-provider factory (no parser source). String-
    // based, mirroring the messages the retired CliArgsException used.

    /** A required flag/sub is absent or blank (USAGE-worthy). */
    data class MissingRequired(val argName: String, val detail: String? = null) : MissingArg {
        override val message = "Missing required argument $argName${detail?.let { ": $it" } ?: "."}"
    }

    /** A value failed a post-parse semantic check (bad combination, unknown option, …). */
    data class Invalid(val argName: String, val rawValue: String, val expectedType: String) : ParseError {
        override val message = "$argName must be $expectedType, got \"$rawValue\"."
    }

    /** A flag's value carries more sub-values than the cap allows. */
    data class TooManyValues(val argName: String, val count: Int, val maxAllowed: Int) : ParseError {
        override val message = "$argName accepts up to $maxAllowed values, got $count."
    }
}

/**
 * Small read helpers over the parsed cliargs controls, shared by the command
 * mapper and the model-provider factory.
 */
internal fun ParsedArg.subValue(arg: CliArg): String? = sub(arg)?.value
internal fun List<ParsedArg>.last(arg: CliArg): ParsedArg? = lastOrNull { it.arg == arg }
internal fun List<ParsedArg>.has(arg: CliArg): Boolean = any { it.arg == arg }
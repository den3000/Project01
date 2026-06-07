package ru.den.writes.code.project01.cliJvm

private const val ARG_PREFIX = "-"
private const val ARG_PROMPT = "${ARG_PREFIX}prompt"
private const val ARG_MAX_TOKENS = "${ARG_PREFIX}maxTokens"
private const val ARG_STOP_SEQUENCE = "${ARG_PREFIX}stopSequence"
private const val ARG_END_SEQUENCE = "${ARG_PREFIX}endSequence"
private const val ARG_TEMPERATURE = "${ARG_PREFIX}temperature"

/**
 * Errors raised by [CliArgs.from]. Each subclass carries the data the caller
 * needs to render a meaningful message; the caller decides how/where to print.
 */
sealed class CliArgsException(message: String) : RuntimeException(message) {
    /** Thrown when a required flag is missing or its value is blank. */
    class MissingRequiredArgument(val argName: String) :
        CliArgsException("Missing required argument $argName.")

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

/**
 * Strongly-typed view of the CLI arguments accepted by the app.
 *
 * Use [CliArgs.from] to build an instance from the raw `args: Array<String>`
 * passed to `main`. Parsing throws [CliArgsException] on invalid input — the
 * caller catches and prints.
 */
data class CliArgs(
    val prompt: String,
    val maxTokens: Int?,
    /**
     * Words extracted from `-stopSequence` (split on whitespace). Each element
     * becomes a separate entry in Gemini's `generationConfig.stopSequences`,
     * any one of which halts generation when it appears in the output.
     */
    val stopSequences: List<String>?,
    val endSequence: String?,
    /**
     * Sampling temperature for Gemini's `generationConfig.temperature`.
     * Higher = more random / creative, lower = more deterministic.
     * Gemini accepts roughly 0.0..2.0; out-of-range values are rejected by
     * the API, not validated here.
     */
    val temperature: Double?,
) {
    companion object {
        /** Gemini API limit on the number of stop sequences. */
        const val MAX_STOP_SEQUENCES: Int = 5

        /** One-line usage hint suitable for printing alongside an error message. */
        const val USAGE: String =
            "Usage: $ARG_PROMPT <text> " +
                "[$ARG_MAX_TOKENS <int>] " +
                "[$ARG_STOP_SEQUENCE <words>] " +
                "[$ARG_END_SEQUENCE <text>] " +
                "[$ARG_TEMPERATURE <number 0.0..2.0>]"

        /**
         * Parses CLI arguments of the form:
         *   -prompt <text...>       (required, arbitrary length, may contain spaces)
         *   -maxTokens <int>        (optional)
         *   -stopSequence <words>   (optional, up to $MAX_STOP_SEQUENCES whitespace-separated
         *                            words; each becomes its own stop sequence)
         *   -endSequence <text>     (optional, arbitrary length, may contain spaces;
         *                            sent via systemInstruction, best-effort)
         *   -temperature <number>   (optional, decimal; forwarded as
         *                            `generationConfig.temperature`)
         *
         * Tokens following a known flag are joined with spaces until the next
         * known flag, so unquoted multi-word values work too:
         *   -prompt tell me a joke -maxTokens 100
         *
         * @throws CliArgsException.MissingRequiredArgument if `-prompt` is absent or blank.
         * @throws CliArgsException.InvalidArgumentValue if `-maxTokens` is not an integer
         *   or `-temperature` is not a decimal number.
         * @throws CliArgsException.TooManyValues if `-stopSequence` has more than
         *   [MAX_STOP_SEQUENCES] whitespace-separated words.
         */
        fun from(args: Array<String>): CliArgs {
            val knownFlags = setOf(
                ARG_PROMPT,
                ARG_MAX_TOKENS,
                ARG_STOP_SEQUENCE,
                ARG_END_SEQUENCE,
                ARG_TEMPERATURE,
            )
            val values = mutableMapOf<String, String>()
            var currentFlag: String? = null
            val buffer = StringBuilder()

            fun flush() {
                currentFlag?.let { values[it] = buffer.toString().trim() }
                buffer.setLength(0)
            }

            for (token in args) {
                if (token in knownFlags) {
                    flush()
                    currentFlag = token
                } else if (currentFlag != null) {
                    if (buffer.isNotEmpty()) buffer.append(' ')
                    buffer.append(token)
                }
            }
            flush()

            val prompt = values[ARG_PROMPT]?.takeIf { it.isNotBlank() }
                ?: throw CliArgsException.MissingRequiredArgument(ARG_PROMPT)

            val maxTokens = values[ARG_MAX_TOKENS]?.let { raw ->
                raw.toIntOrNull()
                    ?: throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_MAX_TOKENS,
                        rawValue = raw,
                        expectedType = "an integer",
                    )
            }

            // Split -stopSequence on any whitespace run, drop empties.
            // Each resulting word becomes a separate stop sequence; Gemini
            // caps the list at MAX_STOP_SEQUENCES entries.
            val stopSequences = values[ARG_STOP_SEQUENCE]
                ?.takeIf { it.isNotBlank() }
                ?.split(Regex("\\s+"))
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?.also { list ->
                    if (list.size > MAX_STOP_SEQUENCES) {
                        throw CliArgsException.TooManyValues(
                            argName = ARG_STOP_SEQUENCE,
                            count = list.size,
                            maxAllowed = MAX_STOP_SEQUENCES,
                        )
                    }
                }

            val temperature = values[ARG_TEMPERATURE]?.let { raw ->
                raw.toDoubleOrNull()
                    ?: throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_TEMPERATURE,
                        rawValue = raw,
                        expectedType = "a decimal number",
                    )
            }

            return CliArgs(
                prompt = prompt,
                maxTokens = maxTokens,
                stopSequences = stopSequences,
                endSequence = values[ARG_END_SEQUENCE]?.takeIf { it.isNotBlank() },
                temperature = temperature,
            )
        }
    }
}

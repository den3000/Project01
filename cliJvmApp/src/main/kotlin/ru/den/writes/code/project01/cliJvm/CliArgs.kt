package ru.den.writes.code.project01.cliJvm

private const val ARG_PREFIX = "-"
private const val ARG_PROMPT = "${ARG_PREFIX}prompt"
private const val ARG_MAX_TOKENS = "${ARG_PREFIX}maxTokens"
private const val ARG_STOP_SEQUENCE = "${ARG_PREFIX}stopSequence"
private const val ARG_END_SEQUENCE = "${ARG_PREFIX}endSequence"
private const val ARG_TEMPERATURE = "${ARG_PREFIX}temperature"
private const val ARG_MODEL = "${ARG_PREFIX}model"
private const val ARG_SESSION = "${ARG_PREFIX}session"
private const val ARG_LIST_SESSIONS = "${ARG_PREFIX}sessions"

private val SESSION_NAME_REGEX = Regex("^[a-zA-Z0-9_-]+$")
private const val MAX_SESSION_NAME_LENGTH = 64

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
 * Two modes:
 * - **Run mode** (default): drives a chat session against the LLM. Requires
 *   `-prompt`; everything else optional.
 * - **List mode** (`-sessions`): prints the list of known sessions and exits.
 *   Sets [listSessions] true; all other fields are null/false.
 *
 * Use [CliArgs.from] to build an instance from the raw `args: Array<String>`
 * passed to `main`. Parsing throws [CliArgsException] on invalid input — the
 * caller catches and prints.
 */
data class CliArgs(
    /**
     * The opening user turn for run mode. Null only when [listSessions] is true.
     */
    val prompt: String?,
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
    /**
     * Gemini text-generation model id. Per Google's catalog
     * (https://ai.google.dev/gemini-api/docs/models), as of June 2026:
     * - 2.5 GA: `gemini-2.5-pro`, `gemini-2.5-flash`, `gemini-2.5-flash-lite`
     * - 3.1 preview: `gemini-3.1-pro-preview`, `gemini-3-flash-preview`
     *   (note: the 3.1 Flash id omits the ".1" — that's how Google ships it),
     *   `gemini-3.1-flash-lite` (GA)
     * - 3.5: `gemini-3.5-flash` only — no Pro, no Flash-Lite in 3.5
     *
     * When null the caller falls back to its own default. Validity isn't
     * checked here — unknown ids are rejected by the API.
     */
    val model: String?,
    /**
     * Session name for `-session NAME` — picks which conversation history to
     * resume (or starts a new one with that name if it doesn't exist yet).
     * Null means «generate a fresh random session id at startup».
     *
     * Naming rules: `^[a-zA-Z0-9_-]+$`, up to [MAX_SESSION_NAME_LENGTH] chars.
     * Out-of-shape names are rejected to keep the value safe for SQL filtering
     * and human-readable in `-sessions` output.
     */
    val session: String?,
    /**
     * `-sessions` mode: print the list of known sessions with message counts
     * and exit. All other fields are ignored when this is true.
     */
    val listSessions: Boolean,
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
                "[$ARG_TEMPERATURE <number 0.0..2.0>] " +
                "[$ARG_MODEL <model-id>] " +
                "[$ARG_SESSION <name>]\n" +
                "   or: $ARG_LIST_SESSIONS   (lists saved sessions, ignores all other flags)"

        /**
         * Parses CLI arguments of the form:
         *   -prompt <text...>       (required in run mode; arbitrary length, may
         *                            contain spaces)
         *   -maxTokens <int>        (optional)
         *   -stopSequence <words>   (optional, up to $MAX_STOP_SEQUENCES whitespace-separated
         *                            words; each becomes its own stop sequence)
         *   -endSequence <text>     (optional, arbitrary length, may contain spaces;
         *                            sent via systemInstruction, best-effort)
         *   -temperature <number>   (optional, decimal; forwarded as
         *                            `generationConfig.temperature`)
         *   -model <model-id>       (optional; see [CliArgs.model] for the verified
         *                            list — covers Pro / Flash / Flash-Lite tiers
         *                            of generations 2.5, 3.1, 3.5 where they exist.
         *                            Caller picks a default when this is null)
         *   -session <name>         (optional; matches `^[a-zA-Z0-9_-]+$`, ≤ 64
         *                            chars. Picks which saved session to resume,
         *                            or starts a new one with that name. Omit
         *                            to start a fresh session with a generated id)
         *   -sessions               (flag-only; switches to list mode and ignores
         *                            every other flag)
         *
         * Tokens following a known flag are joined with spaces until the next
         * known flag, so unquoted multi-word values work too:
         *   -prompt tell me a joke -maxTokens 100
         *
         * @throws CliArgsException.MissingRequiredArgument if `-prompt` is absent or blank
         *   in run mode (list mode does not require `-prompt`).
         * @throws CliArgsException.InvalidArgumentValue if `-maxTokens` is not an integer,
         *   `-temperature` is not a decimal number, or `-session` is malformed.
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
                ARG_MODEL,
                ARG_SESSION,
                ARG_LIST_SESSIONS,
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

            // List mode short-circuits all other validation: if `-sessions` is
            // present we won't be running a chat session at all, so no point
            // in checking the other flags.
            if (ARG_LIST_SESSIONS in values) {
                return CliArgs(
                    prompt = null,
                    maxTokens = null,
                    stopSequences = null,
                    endSequence = null,
                    temperature = null,
                    model = null,
                    session = null,
                    listSessions = true,
                )
            }

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

            val session = values[ARG_SESSION]?.takeIf { it.isNotBlank() }?.let { raw ->
                if (raw.length > MAX_SESSION_NAME_LENGTH || !raw.matches(SESSION_NAME_REGEX)) {
                    throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_SESSION,
                        rawValue = raw,
                        expectedType = "alphanumeric / '_' / '-', up to $MAX_SESSION_NAME_LENGTH chars",
                    )
                }
                raw
            }

            return CliArgs(
                prompt = prompt,
                maxTokens = maxTokens,
                stopSequences = stopSequences,
                endSequence = values[ARG_END_SEQUENCE]?.takeIf { it.isNotBlank() },
                temperature = temperature,
                model = values[ARG_MODEL]?.takeIf { it.isNotBlank() },
                session = session,
                listSessions = false,
            )
        }
    }
}

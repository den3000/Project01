package ru.den.writes.code.project01.cliJvm

private const val ARG_PREFIX = "-"
private const val ARG_PROMPT = "${ARG_PREFIX}prompt"
private const val ARG_MAX_TOKENS = "${ARG_PREFIX}maxTokens"
private const val ARG_STOP_SEQUENCE = "${ARG_PREFIX}stopSequence"
private const val ARG_END_SEQUENCE = "${ARG_PREFIX}endSequence"
private const val ARG_TEMPERATURE = "${ARG_PREFIX}temperature"
private const val ARG_MODEL = "${ARG_PREFIX}model"
private const val ARG_PROVIDER = "${ARG_PREFIX}provider"
private const val ARG_SESSION = "${ARG_PREFIX}session"
private const val ARG_LIST_SESSIONS = "${ARG_PREFIX}sessions"
private const val ARG_CLEAN = "${ARG_PREFIX}clean"
private const val ARG_ONESHOT = "${ARG_PREFIX}oneshot"

private const val PROVIDER_GEMINI = "gemini"
private const val PROVIDER_OPENROUTER = "openrouter"

private val SESSION_NAME_REGEX = Regex("^[a-zA-Z0-9_-]+$")
private const val MAX_SESSION_NAME_LENGTH = 64

/**
 * Errors raised by [CliArgs.from]. Each subclass carries the data the caller
 * needs to render a meaningful message; the caller decides how/where to print.
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

/**
 * Strongly-typed view of the CLI arguments accepted by the app.
 *
 * Four modes, modelled as a sealed hierarchy:
 * - [ListSessions] (`-sessions`) — list known session ids, exit.
 * - [Clean] (`-clean`) — wipe ALL session history from the DB, exit.
 * - [Chat] (default `-prompt …`) — interactive REPL with persisted history.
 * - [OneShot] (`-prompt … -oneshot`) — single prompt, single response, exit;
 *   no DB, no session, no REPL.
 *
 * The shared LLM-talking modes ([Chat], [OneShot]) implement
 * [PromptCommand] so the agent can take a single type and decide
 * behaviour internally. The chosen provider (Gemini / OpenRouter) and
 * its typed model travel as one [ModelProvider] field — the provider
 * does not fan out into per-provider variants of `Chat`/`OneShot`.
 *
 * Use [CliArgs.from] to build an instance from the raw `args: Array<String>`
 * passed to `main`. Parsing throws [CliArgsException] on invalid input — the
 * caller catches and prints.
 */
internal sealed interface CliArgs {
    /** `-sessions`: print the saved-session list and exit. */
    data object ListSessions : CliArgs

    /** `-clean`: delete every row from the messages table and exit. */
    data object Clean : CliArgs

    /**
     * Common shape for the LLM-talking modes ([Chat], [OneShot]). Lets
     * `Agent` accept one type without losing the generation knobs.
     */
    sealed interface PromptCommand : CliArgs {
        val prompt: String
        val maxTokens: Int?
        val stopSequences: List<String>?
        val endSequence: String?
        val temperature: Double?

        /**
         * Which provider to talk to + the typed model + its API key,
         * bundled together. The discriminator `main.kt` uses to pick
         * the right [LlmApi] implementation.
         */
        val modelProvider: ModelProvider
    }

    /**
     * Standard interactive mode. Opening `-prompt` is sent; agent enters
     * a REPL afterwards, persisting every successful turn to the session
     * named by [session] (or a generated id when null).
     */
    data class Chat(
        override val prompt: String,
        override val maxTokens: Int?,
        override val stopSequences: List<String>?,
        override val endSequence: String?,
        override val temperature: Double?,
        override val modelProvider: ModelProvider,
        /**
         * Session name for `-session NAME` — picks which conversation
         * history to resume (or starts a new one with that name if it
         * doesn't exist yet). Null means «generate a fresh random
         * session id at startup».
         *
         * Naming rules: `^[a-zA-Z0-9_-]+$`, up to
         * [MAX_SESSION_NAME_LENGTH] chars.
         */
        val session: String?,
    ) : PromptCommand

    /**
     * Single-turn fire-and-forget. Sends the prompt, prints the model's
     * response, exits. Does NOT load or save any history — there's
     * intentionally no `session` field because OneShot has no session.
     * Useful for quick questions where you don't want to pollute your
     * persisted history.
     */
    data class OneShot(
        override val prompt: String,
        override val maxTokens: Int?,
        override val stopSequences: List<String>?,
        override val endSequence: String?,
        override val temperature: Double?,
        override val modelProvider: ModelProvider,
    ) : PromptCommand

    companion object {
        /** Gemini API limit on the number of stop sequences. */
        const val MAX_STOP_SEQUENCES: Int = 5

        /** One-line usage hint suitable for printing alongside an error message. */
        const val USAGE: String =
            "Usage: $ARG_PROMPT <text> " +
                "[$ARG_PROVIDER <$PROVIDER_GEMINI|$PROVIDER_OPENROUTER>] " +
                "[$ARG_MODEL <model-id>] " +
                "[$ARG_MAX_TOKENS <int>] " +
                "[$ARG_STOP_SEQUENCE <words>] " +
                "[$ARG_END_SEQUENCE <text>] " +
                "[$ARG_TEMPERATURE <number 0.0..2.0>] " +
                "[$ARG_SESSION <name>]\n" +
                "   or: $ARG_PROMPT <text> $ARG_ONESHOT [...same knobs as above, no $ARG_SESSION]\n" +
                "                (single prompt → response → exit; no REPL, no session)\n" +
                "   or: $ARG_LIST_SESSIONS   (list saved sessions, ignores all other flags)\n" +
                "   or: $ARG_CLEAN      (delete ALL session history, ignores all other flags)\n" +
                "Default provider is $PROVIDER_GEMINI."

        /**
         * Parses CLI arguments and dispatches to the right [CliArgs] variant.
         *
         * Mode-selection rules:
         * - If more than one of `-sessions`, `-clean`, `-oneshot` is given,
         *   throws [CliArgsException.InvalidArgumentValue] — they're mutually
         *   exclusive.
         * - `-sessions` wins: returns [ListSessions], ignores everything else.
         * - `-clean` wins: returns [Clean], ignores everything else.
         * - `-oneshot` requires `-prompt` and rejects `-session`.
         * - Otherwise: standard [Chat] with required `-prompt`.
         *
         * Provider rules:
         * - `-provider <gemini|openrouter>` picks the provider. Default is
         *   `gemini`. Any other value → [CliArgsException.InvalidArgumentValue].
         * - `-model <id>` parses through the typed model for the chosen
         *   provider — unknown ids fall through to a `Custom(id)` variant.
         * - The API key for the chosen provider must be non-blank;
         *   otherwise [CliArgsException.MissingRequiredArgument] (with a
         *   hint about local.properties / env var).
         *
         * @param geminiApiKey resolved Gemini key (BuildKonfig + env var
         *   merge in `main.kt`); empty string if not configured.
         * @param openRouterApiKey resolved OpenRouter key; empty string if
         *   not configured.
         *
         * @throws CliArgsException.MissingRequiredArgument if `-prompt` is absent
         *   or blank in Chat / OneShot modes, or the selected provider's API
         *   key is blank.
         * @throws CliArgsException.InvalidArgumentValue if `-maxTokens` is not an
         *   integer, `-temperature` is not a decimal number, `-session` is
         *   malformed, `-session` is given alongside `-oneshot`, `-provider`
         *   is unknown, or two mode flags are given simultaneously.
         * @throws CliArgsException.TooManyValues if `-stopSequence` has more than
         *   [MAX_STOP_SEQUENCES] whitespace-separated words.
         */
        fun from(
            args: Array<String>,
            geminiApiKey: String = "",
            openRouterApiKey: String = "",
        ): CliArgs {
            val knownFlags = setOf(
                ARG_PROMPT,
                ARG_MAX_TOKENS,
                ARG_STOP_SEQUENCE,
                ARG_END_SEQUENCE,
                ARG_TEMPERATURE,
                ARG_MODEL,
                ARG_PROVIDER,
                ARG_SESSION,
                ARG_LIST_SESSIONS,
                ARG_CLEAN,
                ARG_ONESHOT,
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

            // Mutual-exclusivity check for the three mode-selecting flags.
            // Picked up early so an obvious mis-typing fails fast before we
            // run any partial validation of the other args.
            val isList = ARG_LIST_SESSIONS in values
            val isClean = ARG_CLEAN in values
            val isOneShot = ARG_ONESHOT in values
            if (listOf(isList, isClean, isOneShot).count { it } > 1) {
                throw CliArgsException.InvalidArgumentValue(
                    argName = "$ARG_LIST_SESSIONS / $ARG_CLEAN / $ARG_ONESHOT",
                    rawValue = "(multiple)",
                    expectedType = "at most one of $ARG_LIST_SESSIONS, $ARG_CLEAN, $ARG_ONESHOT at a time",
                )
            }

            if (isList) return ListSessions
            if (isClean) return Clean

            // Past this point we're in a PromptCommand mode (Chat or OneShot).
            // All the same fields apply, with one wrinkle: `-session` is
            // forbidden in OneShot.

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

            val endSequence = values[ARG_END_SEQUENCE]?.takeIf { it.isNotBlank() }
            val modelRaw = values[ARG_MODEL]?.takeIf { it.isNotBlank() }
            val providerRaw = values[ARG_PROVIDER]?.takeIf { it.isNotBlank() } ?: PROVIDER_GEMINI
            val modelProvider = buildModelProvider(
                providerRaw = providerRaw,
                modelRaw = modelRaw,
                geminiApiKey = geminiApiKey,
                openRouterApiKey = openRouterApiKey,
            )

            if (isOneShot) {
                // -session NAME doesn't make sense for a fire-and-forget call:
                // we wouldn't load any history (so resume is meaningless) and
                // we wouldn't write any history (so creating a named session
                // is misleading). Reject loudly instead of silently ignoring.
                values[ARG_SESSION]?.takeIf { it.isNotBlank() }?.let { raw ->
                    throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_SESSION,
                        rawValue = raw,
                        expectedType = "absent (not compatible with $ARG_ONESHOT)",
                    )
                }
                return OneShot(
                    prompt = prompt,
                    maxTokens = maxTokens,
                    stopSequences = stopSequences,
                    endSequence = endSequence,
                    temperature = temperature,
                    modelProvider = modelProvider,
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

            return Chat(
                prompt = prompt,
                maxTokens = maxTokens,
                stopSequences = stopSequences,
                endSequence = endSequence,
                temperature = temperature,
                modelProvider = modelProvider,
                session = session,
            )
        }

        private fun buildModelProvider(
            providerRaw: String,
            modelRaw: String?,
            geminiApiKey: String,
            openRouterApiKey: String,
        ): ModelProvider = when (providerRaw) {
            PROVIDER_GEMINI -> {
                if (geminiApiKey.isBlank()) {
                    throw CliArgsException.MissingRequiredArgument(
                        argName = "GEMINI_API_KEY",
                        detail = "set GEMINI_API_KEY in local.properties or as an env var",
                    )
                }
                ModelProvider.Gemini(
                    model = modelRaw?.let(GeminiModel.Companion::fromId) ?: GeminiModel.Default,
                    apiKey = geminiApiKey,
                )
            }
            PROVIDER_OPENROUTER -> {
                if (openRouterApiKey.isBlank()) {
                    throw CliArgsException.MissingRequiredArgument(
                        argName = "OPENROUTER_API_KEY",
                        detail = "set OPENROUTER_API_KEY in local.properties or as an env var",
                    )
                }
                ModelProvider.OpenRouter(
                    model = modelRaw?.let(OpenRouterModel.Companion::fromId) ?: OpenRouterModel.Default,
                    apiKey = openRouterApiKey,
                )
            }
            else -> throw CliArgsException.InvalidArgumentValue(
                argName = ARG_PROVIDER,
                rawValue = providerRaw,
                expectedType = "one of: $PROVIDER_GEMINI, $PROVIDER_OPENROUTER",
            )
        }
    }
}

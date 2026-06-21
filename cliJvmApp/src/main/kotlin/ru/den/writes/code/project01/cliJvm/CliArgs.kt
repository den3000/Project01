package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.shared.llm.gemini.GeminiModel
import ru.den.writes.code.project01.shared.llm.huggingface.HuggingFaceModel
import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.shared.llm.openrouter.OpenRouterModel
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.ProfileCommand
import ru.den.writes.code.project01.shared.memory.ProfileSection
import ru.den.writes.code.project01.shared.memory.TaskBinding
import ru.den.writes.code.project01.shared.memory.TaskStage
import ru.den.writes.code.project01.shared.memory.parseProfileCommand

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
private const val ARG_TUI = "${ARG_PREFIX}tui"
private const val ARG_FEED_FILE = "${ARG_PREFIX}feedFile"
private const val ARG_CHUNK_CHARS = "${ARG_PREFIX}chunkChars"
private const val ARG_FEED_INSTRUCTION = "${ARG_PREFIX}feedInstruction"
private const val ARG_BY_LINE = "${ARG_PREFIX}byLine"
private const val ARG_INFLATE = "${ARG_PREFIX}inflate"
private const val ARG_COMPRESS = "${ARG_PREFIX}compress"
private const val ARG_KEEP_LAST = "${ARG_PREFIX}keepLast"
private const val ARG_SUMMARIZE_EVERY = "${ARG_PREFIX}summarizeEvery"
private const val ARG_STRATEGY = "${ARG_PREFIX}strategy"
private const val ARG_MEMORY = "${ARG_PREFIX}memory"
private const val ARG_TASK = "${ARG_PREFIX}task"
private const val ARG_PROFILE = "${ARG_PREFIX}profile"
private const val ARG_MEMORY_MODE = "${ARG_PREFIX}memory-mode"
private const val ARG_STAGE_AGENT = "${ARG_PREFIX}stageAgent"
private const val ARG_JUDGE_AGENT = "${ARG_PREFIX}judgeAgent"

// -memory-mode values (matched case-insensitively).
private const val MEMORY_MODE_PREAMBLE = "preamble"
private const val MEMORY_MODE_SYSTEM = "system"

// -strategy values (matched case-insensitively).
private const val STRATEGY_FULL = "full"
private const val STRATEGY_WINDOW = "window"
private const val STRATEGY_FACTS = "facts"
private const val STRATEGY_SUMMARY = "summary"

private const val PROVIDER_GEMINI = "gemini"
private const val PROVIDER_OPENROUTER = "openrouter"
private const val PROVIDER_HUGGINGFACE = "huggingface"

private val SESSION_NAME_REGEX = Regex("^[a-zA-Z0-9_-]+$")
private const val MAX_SESSION_NAME_LENGTH = 64

/** Default chunk size for `-feedFile` when `-chunkChars` is omitted. */
private const val DEFAULT_CHUNK_CHARS = 2500

/** Default number of recent messages kept verbatim under `-compress`. */
private const val DEFAULT_KEEP_LAST = 6

/** Default fold threshold under `-compress` (messages beyond the kept tail). */
private const val DEFAULT_SUMMARIZE_EVERY = 10

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
 * behaviour internally. The chosen provider (Gemini / OpenRouter /
 * Hugging Face) and its typed model travel as one [ModelProvider]
 * field — the provider does not fan out into per-provider variants
 * of `Chat`/`OneShot`.
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
     * `-inflate N -session NAME`: copy the last N message rows of a
     * session and append them to the same session, then exit. No API
     * calls — pure DB operation. Used to fast-forward a session's
     * context-window fill for stress testing without paying for or
     * waiting on rate-limited LLM calls.
     *
     * Copied rows preserve `text` + `role` only; `model_id` and the
     * token counts are NULL on the copies so [SessionStats] doesn't
     * double-count what was previously billed.
     */
    data class Inflate(val sessionId: String, val n: Int) : CliArgs

    /**
     * `-memory <subcommand>`: read or write the long-term / working
     * memory files under `~/.project01-cli/memory/`, then exit. Pure
     * disk operation, no LLM and no session — these commands manage the
     * data the agent later pulls into its prompt via `-memory-mode`.
     */
    data class Memory(val action: MemoryAction) : CliArgs

    /** What `-memory` does this invocation. */
    sealed interface MemoryAction {
        /** Print every layer (mode, profile, rules, active task). */
        data object Show : MemoryAction
        /** Overwrite `profile.md` with [text] (legacy free-text path). */
        data class SetProfile(val text: String) : MemoryAction
        /** Append [text] to a structured [section] of the unnamed profile. */
        data class AddProfileItem(val section: ProfileSection, val text: String) : MemoryAction
        /** Empty a single section of the unnamed profile. */
        data class ClearProfileSection(val section: ProfileSection) : MemoryAction
        /** Drop the unnamed profile entirely (including any legacy free text). */
        data object ClearProfile : MemoryAction

        // --- Named profiles -----------------------------------------
        /** List all named profiles under `profiles/`. */
        data object ListProfiles : MemoryAction
        /** Show the structure of one named profile. */
        data class ShowProfile(val name: String) : MemoryAction
        /** Create an empty `profiles/<name>.md` if it doesn't exist yet. */
        data class TouchProfile(val name: String) : MemoryAction
        /** Append [text] to [section] of named profile [name]. */
        data class AddNamedProfileItem(val name: String, val section: ProfileSection, val text: String) : MemoryAction
        /** Empty [section] of named profile [name]. */
        data class ClearNamedProfileSection(val name: String, val section: ProfileSection) : MemoryAction
        /** Delete the entire named profile file. */
        data class ClearNamedProfile(val name: String) : MemoryAction

        /** Append a new rule under `rules/`. */
        data class AddRule(val text: String) : MemoryAction
        /** Delete the rule with this id (three-digit prefix). */
        data class RemoveRule(val id: String) : MemoryAction
        /** Create/select a task file under `tasks/<taskId>.md`. */
        data class SetTask(val taskId: String) : MemoryAction
        /** Pause the task — hold its FSM stage. */
        data class PauseTask(val taskId: String) : MemoryAction
        /** Resume the task — clear the pause flag. */
        data class ResumeTask(val taskId: String) : MemoryAction
    }

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
        /**
         * Path to a file to feed to the model in chunks instead of
         * reading prompts from stdin. Each successful turn pulls the
         * next [chunkChars] characters from the file and sends them as
         * the user prompt; the loop stops when the file is exhausted
         * or the provider returns an error.
         *
         * When null, `Agent` falls back to its default
         * [StdinPromptSource]. Incompatible with [OneShot] (rejected at
         * parse time).
         */
        val feedFile: String?,
        /**
         * Chunk size (in characters, not bytes — UTF-8 safe) for feed
         * mode. Defaults to [DEFAULT_CHUNK_CHARS]; ignored when
         * [feedFile] is null.
         */
        val chunkChars: Int,
        /**
         * Optional prefix prepended to each chunk before sending — e.g.
         * "Briefly comment on the following text:". Empty string means
         * "send the chunk as-is, no wrap". Ignored when [feedFile] is
         * null.
         */
        val feedInstruction: String,
        /**
         * `-byLine`: in feed mode, split [feedFile] into **lines** (one
         * line = one turn, blank lines skipped) instead of [chunkChars]-
         * sized character chunks. Ignored when [feedFile] is null;
         * incompatible with [chunkChars].
         */
        val byLine: Boolean,
        /**
         * Context-management strategy selected via `-strategy`
         * (`-compress` is the shorthand for [ContextStrategyKind.SUMMARY]).
         * Defaults to [ContextStrategyKind.FULL] — ship the whole history
         * each turn. `main` maps this to a concrete [ContextStrategy].
         * Chat-only — OneShot has no history, so the flags are rejected
         * there at parse time.
         */
        val strategy: ContextStrategyKind,
        /**
         * `-keepLast N`: trailing messages kept verbatim. Used by the
         * sliding-window and summary strategies (snapped down to even).
         * Defaults to [DEFAULT_KEEP_LAST]; ignored under
         * [ContextStrategyKind.FULL].
         */
        val keepLast: Int,
        /**
         * `-summarizeEvery M`: fold threshold for the summary strategy —
         * summarize once at least this many messages pile up beyond the kept
         * tail. Defaults to [DEFAULT_SUMMARIZE_EVERY]; only meaningful under
         * [ContextStrategyKind.SUMMARY].
         */
        val summarizeEvery: Int,
        /**
         * `-task <id>`: pre-select an active working-memory task for the
         * session. When [memoryMode] is null this is ignored; otherwise
         * the agent injects the matching `tasks/<id>.md` file (if any)
         * into every turn and `/task-note` appends to it. Null = no
         * active task at startup; switch with `/task <id>` in the REPL.
         *
         * Naming rules: same shape as session (`^[a-zA-Z0-9_-]+$`, up to
         * [MAX_SESSION_NAME_LENGTH] chars).
         */
        val task: String?,
        /**
         * `-profile <name>`: pre-select the active **named** profile for
         * this session. Like [task] only effective when [memoryMode] is
         * non-null. Null means «use the unnamed `profile.md` fallback».
         * Switchable mid-session via `/profile-use <name>`.
         */
        val profile: String?,
        /**
         * `-memory-mode <preamble|system>`: enables the memory layer for
         * this session. Null (default) leaves memory dormant — wire
         * shape is identical to a no-memory chat. PREAMBLE injects one
         * USER/ASSISTANT pair carrying every non-empty layer; SYSTEM
         * emits dedicated `Role.SYSTEM` messages the provider lifts into
         * its native system slot. Switchable mid-session via
         * `/memory-mode`.
         */
        val memoryMode: MemoryMode?,
        /**
         * `-stageAgent <from..to>=<provider>:<model>[@<profile>]` (repeatable):
         * agents bound to FSM stage spans. Each turn routes to the agent whose
         * span covers the active task stage; uncovered stages use the default
         * agent built from `-provider`/`-model`/`-profile`. Empty = single-agent.
         * Requires [memoryMode].
         */
        val stageAgents: List<StageAgentSpec>,
        /**
         * `-tui`: opt into the Kotter+Mordant terminal UI. Only honoured for an
         * interactive Chat on a real TTY (`main` gates on `System.console()`);
         * feed / oneshot / non-TTY fall back to the plain renderer. Rejected
         * with `-oneshot` at parse time.
         */
        val tui: Boolean,
        /**
         * `-judgeAgent <from..to>=<provider>:<model>` (repeatable): per-stage
         * invariant judges. After each turn the judge whose span covers the
         * active task stage audits the reply against the global rules plus the
         * answering agent's profile constraints; a breach suppresses the turn
         * (shown, not persisted; stage held). Empty = no judging. Requires at
         * least one [stageAgents] entry (and thus [memoryMode]).
         */
        val judgeAgents: List<StageJudgeSpec>,
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
                "[$ARG_PROVIDER <$PROVIDER_GEMINI|$PROVIDER_OPENROUTER|$PROVIDER_HUGGINGFACE>] " +
                "[$ARG_MODEL <model-id>] " +
                "[$ARG_MAX_TOKENS <int>] " +
                "[$ARG_STOP_SEQUENCE <words>] " +
                "[$ARG_END_SEQUENCE <text>] " +
                "[$ARG_TEMPERATURE <number 0.0..2.0>] " +
                "[$ARG_SESSION <name>] [$ARG_TUI]\n" +
                "       [$ARG_FEED_FILE <path> [$ARG_CHUNK_CHARS <int> | $ARG_BY_LINE] [$ARG_FEED_INSTRUCTION <text>]]\n" +
                "       [$ARG_STRATEGY <$STRATEGY_FULL|$STRATEGY_WINDOW|$STRATEGY_FACTS|$STRATEGY_SUMMARY> [$ARG_KEEP_LAST <int>] [$ARG_SUMMARIZE_EVERY <int>]]" +
                "  (or $ARG_COMPRESS = $ARG_STRATEGY $STRATEGY_SUMMARY)\n" +
                "       [$ARG_MEMORY_MODE <$MEMORY_MODE_PREAMBLE|$MEMORY_MODE_SYSTEM> [$ARG_TASK <id>] [$ARG_PROFILE <name>]]\n" +
                "       [$ARG_STAGE_AGENT <from..to>=<provider>:<model>[@<profile>] ...]" +
                "  (repeatable; per-stage model+profile; needs $ARG_MEMORY_MODE; uncovered stages use the default agent)\n" +
                "       [$ARG_JUDGE_AGENT <from..to>=<provider>:<model> ...]" +
                "  (repeatable; per-stage invariant judge; needs $ARG_STAGE_AGENT)\n" +
                "   or: $ARG_PROMPT <text> $ARG_ONESHOT [...same knobs as above, no $ARG_SESSION, no $ARG_FEED_FILE]\n" +
                "                (single prompt → response → exit; no REPL, no session)\n" +
                "   or: $ARG_LIST_SESSIONS   (list saved sessions, ignores all other flags)\n" +
                "   or: $ARG_CLEAN      (delete ALL session history, ignores all other flags)\n" +
                "   or: $ARG_INFLATE <N> $ARG_SESSION <name>   (duplicate the last N rows of <name>; no LLM)\n" +
                "   or: $ARG_MEMORY show | profile-list | profile-show <name>\n" +
                "                       | profile [<text> | <section> <text> | <section> clear | clear\n" +
                "                                  | <name> [<section> [<text> | clear] | clear]]\n" +
                "                       | rule add <text> | rule rm <id> | task <id> [pause | resume]\n" +
                "                (profile sections: style | format | constraints | context;\n" +
                "                 unnamed profile = profile.md (fallback when no named profile is active);\n" +
                "                 named profiles live under profiles/<name>.md; no LLM)\n" +
                "REPL branch commands: /branches, /branch <name>, /switch <name>, /checkpoint.\n" +
                "REPL memory commands: /memory, /profile [<text> | <section> <text> | <section> clear | clear], /rule <text>, /task <id>, /task-note <text>, /task-pause, /task-resume, /memory-mode <preamble|system>.\n" +
                "Default provider is $PROVIDER_GEMINI. Default chunk size is $DEFAULT_CHUNK_CHARS chars. " +
                "Strategy tuning defaults: $ARG_KEEP_LAST=$DEFAULT_KEEP_LAST, $ARG_SUMMARIZE_EVERY=$DEFAULT_SUMMARIZE_EVERY."

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
         * - `-provider <gemini|openrouter|huggingface>` picks the provider.
         *   Default is `gemini`. Any other value →
         *   [CliArgsException.InvalidArgumentValue].
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
         * @param huggingFaceApiKey resolved Hugging Face token; empty
         *   string if not configured.
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
            huggingFaceApiKey: String = "",
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
                ARG_FEED_FILE,
                ARG_CHUNK_CHARS,
                ARG_FEED_INSTRUCTION,
                ARG_BY_LINE,
                ARG_INFLATE,
                ARG_COMPRESS,
                ARG_KEEP_LAST,
                ARG_SUMMARIZE_EVERY,
                ARG_STRATEGY,
                ARG_MEMORY,
                ARG_TASK,
                ARG_PROFILE,
                ARG_MEMORY_MODE,
                ARG_STAGE_AGENT,
                ARG_TUI,
                ARG_JUDGE_AGENT,
            )
            val values = mutableMapOf<String, String>()
            // -stageAgent repeats (one per stage span), so it can't live in the
            // last-wins `values` map — each occurrence accumulates here.
            val stageAgentRaws = mutableListOf<String>()
            // -judgeAgent likewise repeats (one judge per stage span).
            val judgeAgentRaws = mutableListOf<String>()
            var currentFlag: String? = null
            val buffer = StringBuilder()

            fun flush() {
                currentFlag?.let { flag ->
                    val v = buffer.toString().trim()
                    if (flag == ARG_STAGE_AGENT) {
                        if (v.isNotEmpty()) stageAgentRaws += v
                    } else if (flag == ARG_JUDGE_AGENT) {
                        if (v.isNotEmpty()) judgeAgentRaws += v
                    } else {
                        values[flag] = v
                    }
                }
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

            // Mutual-exclusivity check for the mode-selecting flags.
            // Picked up early so an obvious mis-typing fails fast before we
            // run any partial validation of the other args.
            val isList = ARG_LIST_SESSIONS in values
            val isClean = ARG_CLEAN in values
            val isOneShot = ARG_ONESHOT in values
            val isMemoryOp = ARG_MEMORY in values
            if (listOf(isList, isClean, isOneShot, isMemoryOp).count { it } > 1) {
                throw CliArgsException.InvalidArgumentValue(
                    argName = "$ARG_LIST_SESSIONS / $ARG_CLEAN / $ARG_ONESHOT / $ARG_MEMORY",
                    rawValue = "(multiple)",
                    expectedType = "at most one of $ARG_LIST_SESSIONS, $ARG_CLEAN, $ARG_ONESHOT, $ARG_MEMORY at a time",
                )
            }

            // -stageAgent is a Chat-only concern: per-stage routing needs a
            // live session + memory. Reject it in every other mode rather than
            // silently dropping it.
            if (stageAgentRaws.isNotEmpty() &&
                (isList || isClean || isOneShot || isMemoryOp || ARG_INFLATE in values)
            ) {
                throw CliArgsException.InvalidArgumentValue(
                    argName = ARG_STAGE_AGENT,
                    rawValue = stageAgentRaws.first(),
                    expectedType = "absent (only valid in chat mode)",
                )
            }
            // -judgeAgent is likewise Chat-only.
            if (judgeAgentRaws.isNotEmpty() &&
                (isList || isClean || isOneShot || isMemoryOp || ARG_INFLATE in values)
            ) {
                throw CliArgsException.InvalidArgumentValue(
                    argName = ARG_JUDGE_AGENT,
                    rawValue = judgeAgentRaws.first(),
                    expectedType = "absent (only valid in chat mode)",
                )
            }

            if (isList) return ListSessions
            if (isClean) return Clean

            // -memory is its own standalone op (pure disk, no LLM, no REPL).
            // It rejects LLM-flow / inflate flags so a typo doesn't quietly
            // turn into a half-configured chat.
            values[ARG_MEMORY]?.takeIf { it.isNotBlank() }?.let { raw ->
                val action = parseMemoryAction(raw)
                listOf(
                    ARG_PROMPT, ARG_FEED_FILE, ARG_CHUNK_CHARS, ARG_FEED_INSTRUCTION,
                    ARG_INFLATE, ARG_TASK, ARG_PROFILE, ARG_MEMORY_MODE,
                ).forEach { flag ->
                    values[flag]?.takeIf { it.isNotBlank() }?.let { conflict ->
                        throw CliArgsException.InvalidArgumentValue(
                            argName = flag,
                            rawValue = conflict,
                            expectedType = "absent (not compatible with $ARG_MEMORY)",
                        )
                    }
                }
                listOf(ARG_COMPRESS, ARG_KEEP_LAST, ARG_SUMMARIZE_EVERY, ARG_BY_LINE, ARG_STRATEGY).forEach { flag ->
                    if (flag in values) throw CliArgsException.InvalidArgumentValue(
                        argName = flag,
                        rawValue = values[flag]?.takeIf { it.isNotBlank() } ?: "(present)",
                        expectedType = "absent (not compatible with $ARG_MEMORY)",
                    )
                }
                return Memory(action)
            }

            // -inflate is its own standalone op (pure DB, no LLM, no
            // REPL). It needs -session NAME and N>0, and it can't coexist
            // with any LLM-flow flag — bail loudly if we see something
            // mixed in.
            values[ARG_INFLATE]?.takeIf { it.isNotBlank() }?.let { raw ->
                val n = raw.toIntOrNull()?.takeIf { it > 0 }
                    ?: throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_INFLATE,
                        rawValue = raw,
                        expectedType = "a positive integer",
                    )
                listOf(
                    ARG_PROMPT, ARG_ONESHOT, ARG_FEED_FILE,
                    ARG_CHUNK_CHARS, ARG_FEED_INSTRUCTION, ARG_TASK, ARG_PROFILE, ARG_MEMORY_MODE,
                ).forEach { flag ->
                    values[flag]?.takeIf { it.isNotBlank() }?.let { conflict ->
                        throw CliArgsException.InvalidArgumentValue(
                            argName = flag,
                            rawValue = conflict,
                            expectedType = "absent (not compatible with $ARG_INFLATE)",
                        )
                    }
                }
                // Compression / line-mode flags are presence-based
                // (-compress / -byLine are value-less), so check membership.
                listOf(ARG_COMPRESS, ARG_KEEP_LAST, ARG_SUMMARIZE_EVERY, ARG_BY_LINE, ARG_STRATEGY).forEach { flag ->
                    if (flag in values) {
                        throw CliArgsException.InvalidArgumentValue(
                            argName = flag,
                            rawValue = values[flag]?.takeIf { it.isNotBlank() } ?: "(present)",
                            expectedType = "absent (not compatible with $ARG_INFLATE)",
                        )
                    }
                }
                val sessionRaw = values[ARG_SESSION]?.takeIf { it.isNotBlank() }
                    ?: throw CliArgsException.MissingRequiredArgument(
                        argName = ARG_SESSION,
                        detail = "$ARG_INFLATE requires an explicit session name",
                    )
                if (sessionRaw.length > MAX_SESSION_NAME_LENGTH || !sessionRaw.matches(SESSION_NAME_REGEX)) {
                    throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_SESSION,
                        rawValue = sessionRaw,
                        expectedType = "alphanumeric / '_' / '-', up to $MAX_SESSION_NAME_LENGTH chars",
                    )
                }
                return Inflate(sessionId = sessionRaw, n = n)
            }

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
                huggingFaceApiKey = huggingFaceApiKey,
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
                // Feed mode loops on a persisted history, which OneShot
                // doesn't have — feeding into a no-history fire-and-forget
                // is a category error, reject up front.
                listOf(ARG_FEED_FILE, ARG_CHUNK_CHARS, ARG_FEED_INSTRUCTION).forEach { flag ->
                    values[flag]?.takeIf { it.isNotBlank() }?.let { raw ->
                        throw CliArgsException.InvalidArgumentValue(
                            argName = flag,
                            rawValue = raw,
                            expectedType = "absent (not compatible with $ARG_ONESHOT)",
                        )
                    }
                }
                // Compression / -byLine / -task / -memory-mode are
                // Chat-only concerns (OneShot has no history, no feed,
                // and no memory). Presence-reject — some of these flags
                // are value-less, so a blank-check wouldn't catch them.
                listOf(
                    ARG_COMPRESS, ARG_KEEP_LAST, ARG_SUMMARIZE_EVERY, ARG_BY_LINE, ARG_STRATEGY,
                    ARG_TASK, ARG_PROFILE, ARG_MEMORY_MODE, ARG_TUI,
                ).forEach { flag ->
                    if (flag in values) {
                        throw CliArgsException.InvalidArgumentValue(
                            argName = flag,
                            rawValue = values[flag]?.takeIf { it.isNotBlank() } ?: "(present)",
                            expectedType = "absent (not compatible with $ARG_ONESHOT)",
                        )
                    }
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

            val feedFile = values[ARG_FEED_FILE]?.takeIf { it.isNotBlank() }
            val chunkChars = values[ARG_CHUNK_CHARS]?.let { raw ->
                raw.toIntOrNull()?.takeIf { it > 0 }
                    ?: throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_CHUNK_CHARS,
                        rawValue = raw,
                        expectedType = "a positive integer",
                    )
            } ?: DEFAULT_CHUNK_CHARS
            // -chunkChars and -feedInstruction only make sense alongside
            // -feedFile; reject them up front so a typo doesn't silently
            // turn into "I configured a chunk size for the REPL".
            if (feedFile == null) {
                values[ARG_CHUNK_CHARS]?.takeIf { it.isNotBlank() }?.let { raw ->
                    throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_CHUNK_CHARS,
                        rawValue = raw,
                        expectedType = "absent (requires $ARG_FEED_FILE)",
                    )
                }
                values[ARG_FEED_INSTRUCTION]?.takeIf { it.isNotBlank() }?.let { raw ->
                    throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_FEED_INSTRUCTION,
                        rawValue = raw,
                        expectedType = "absent (requires $ARG_FEED_FILE)",
                    )
                }
                if (ARG_BY_LINE in values) {
                    throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_BY_LINE,
                        rawValue = "(present)",
                        expectedType = "absent (requires $ARG_FEED_FILE)",
                    )
                }
            }
            // -byLine and -chunkChars are two different ways to split the
            // feed file; having both set is ambiguous, so reject an explicit
            // -chunkChars alongside -byLine.
            val byLine = ARG_BY_LINE in values
            if (byLine) {
                values[ARG_CHUNK_CHARS]?.takeIf { it.isNotBlank() }?.let { raw ->
                    throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_CHUNK_CHARS,
                        rawValue = raw,
                        expectedType = "absent (not compatible with $ARG_BY_LINE)",
                    )
                }
            }
            val feedInstruction = values[ARG_FEED_INSTRUCTION].orEmpty()

            // Context-management strategy. -strategy <full|window|summary>
            // selects it explicitly; -compress is the historical shorthand
            // for "-strategy summary".
            val explicitStrategy = values[ARG_STRATEGY]?.takeIf { it.isNotBlank() }?.let { raw ->
                when (raw.lowercase()) {
                    STRATEGY_FULL -> ContextStrategyKind.FULL
                    STRATEGY_WINDOW -> ContextStrategyKind.WINDOW
                    STRATEGY_FACTS -> ContextStrategyKind.FACTS
                    STRATEGY_SUMMARY -> ContextStrategyKind.SUMMARY
                    else -> throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_STRATEGY,
                        rawValue = raw,
                        expectedType = "one of: $STRATEGY_FULL, $STRATEGY_WINDOW, $STRATEGY_FACTS, $STRATEGY_SUMMARY",
                    )
                }
            }
            // -compress == "-strategy summary": allow it alone or paired with
            // an explicit summary; reject pairing it with any other strategy.
            val compressShorthand = ARG_COMPRESS in values
            if (compressShorthand && explicitStrategy != null && explicitStrategy != ContextStrategyKind.SUMMARY) {
                throw CliArgsException.InvalidArgumentValue(
                    argName = ARG_COMPRESS,
                    rawValue = "(present)",
                    expectedType = "absent — $ARG_COMPRESS is shorthand for $ARG_STRATEGY $STRATEGY_SUMMARY",
                )
            }
            val strategy = explicitStrategy
                ?: if (compressShorthand) ContextStrategyKind.SUMMARY else ContextStrategyKind.FULL

            val keepLast = values[ARG_KEEP_LAST]?.let { raw ->
                raw.toIntOrNull()?.takeIf { it >= 0 }
                    ?: throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_KEEP_LAST,
                        rawValue = raw,
                        expectedType = "a non-negative integer",
                    )
            } ?: DEFAULT_KEEP_LAST
            val summarizeEvery = values[ARG_SUMMARIZE_EVERY]?.let { raw ->
                raw.toIntOrNull()?.takeIf { it >= 2 }
                    ?: throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_SUMMARIZE_EVERY,
                        rawValue = raw,
                        expectedType = "an integer >= 2",
                    )
            } ?: DEFAULT_SUMMARIZE_EVERY
            // -keepLast applies to window + summary; -summarizeEvery to
            // summary only. Reject the tuning flags where they'd be ignored.
            when (strategy) {
                ContextStrategyKind.FULL -> listOf(ARG_KEEP_LAST, ARG_SUMMARIZE_EVERY).forEach { flag ->
                    values[flag]?.takeIf { it.isNotBlank() }?.let { raw ->
                        throw CliArgsException.InvalidArgumentValue(
                            argName = flag,
                            rawValue = raw,
                            expectedType = "absent (requires $ARG_STRATEGY $STRATEGY_WINDOW or $STRATEGY_SUMMARY)",
                        )
                    }
                }
                ContextStrategyKind.WINDOW, ContextStrategyKind.FACTS ->
                    values[ARG_SUMMARIZE_EVERY]?.takeIf { it.isNotBlank() }?.let { raw ->
                        throw CliArgsException.InvalidArgumentValue(
                            argName = ARG_SUMMARIZE_EVERY,
                            rawValue = raw,
                            expectedType = "absent (only valid with $ARG_STRATEGY $STRATEGY_SUMMARY)",
                        )
                    }
                ContextStrategyKind.SUMMARY -> Unit
            }

            // -memory-mode: enable the memory layer for this Chat. Null
            // (flag absent) leaves memory dormant — wire shape identical
            // to a no-memory chat. Any other value is a typo.
            val memoryMode = values[ARG_MEMORY_MODE]?.takeIf { it.isNotBlank() }?.let { raw ->
                when (raw.lowercase()) {
                    MEMORY_MODE_PREAMBLE -> MemoryMode.PREAMBLE
                    MEMORY_MODE_SYSTEM -> MemoryMode.SYSTEM
                    else -> throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_MEMORY_MODE,
                        rawValue = raw,
                        expectedType = "one of: $MEMORY_MODE_PREAMBLE, $MEMORY_MODE_SYSTEM",
                    )
                }
            }
            // -task: same name shape as session. Only meaningful when
            // memory is enabled — otherwise nothing reads it; reject the
            // combination loudly so the user sees the bad input.
            val task = values[ARG_TASK]?.takeIf { it.isNotBlank() }?.let { raw ->
                if (raw.length > MAX_SESSION_NAME_LENGTH || !raw.matches(SESSION_NAME_REGEX)) {
                    throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_TASK,
                        rawValue = raw,
                        expectedType = "alphanumeric / '_' / '-', up to $MAX_SESSION_NAME_LENGTH chars",
                    )
                }
                raw
            }
            if (task != null && memoryMode == null) {
                throw CliArgsException.InvalidArgumentValue(
                    argName = ARG_TASK,
                    rawValue = task,
                    expectedType = "absent (requires $ARG_MEMORY_MODE)",
                )
            }
            // -profile: starting named profile for the Chat. Same name shape
            // as session/task. Like -task it requires -memory-mode.
            val profile = values[ARG_PROFILE]?.takeIf { it.isNotBlank() }?.let { raw ->
                if (raw.length > MAX_SESSION_NAME_LENGTH || !raw.matches(SESSION_NAME_REGEX)) {
                    throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_PROFILE,
                        rawValue = raw,
                        expectedType = "alphanumeric / '_' / '-', up to $MAX_SESSION_NAME_LENGTH chars",
                    )
                }
                raw
            }
            if (profile != null && memoryMode == null) {
                throw CliArgsException.InvalidArgumentValue(
                    argName = ARG_PROFILE,
                    rawValue = profile,
                    expectedType = "absent (requires $ARG_MEMORY_MODE)",
                )
            }
            // -stageAgent: per-stage routing. Like -task/-profile it needs the
            // memory layer on — routing keys off the active task's stage.
            val stageAgents = stageAgentRaws.map { raw ->
                parseStageAgentSpec(raw, geminiApiKey, openRouterApiKey, huggingFaceApiKey)
            }
            if (stageAgents.isNotEmpty() && memoryMode == null) {
                throw CliArgsException.InvalidArgumentValue(
                    argName = ARG_STAGE_AGENT,
                    rawValue = stageAgentRaws.first(),
                    expectedType = "absent (requires $ARG_MEMORY_MODE)",
                )
            }
            // -judgeAgent: per-stage invariant judges, persona-less (model +
            // stage span only). They only make sense alongside per-stage agents,
            // so require at least one -stageAgent (which already implies
            // -memory-mode).
            val judgeAgents = judgeAgentRaws.map { raw ->
                parseJudgeAgentSpec(raw, geminiApiKey, openRouterApiKey, huggingFaceApiKey)
            }
            if (judgeAgents.isNotEmpty() && stageAgents.isEmpty()) {
                throw CliArgsException.InvalidArgumentValue(
                    argName = ARG_JUDGE_AGENT,
                    rawValue = judgeAgentRaws.first(),
                    expectedType = "absent (requires $ARG_STAGE_AGENT)",
                )
            }

            return Chat(
                prompt = prompt,
                maxTokens = maxTokens,
                stopSequences = stopSequences,
                endSequence = endSequence,
                temperature = temperature,
                modelProvider = modelProvider,
                session = session,
                feedFile = feedFile,
                chunkChars = chunkChars,
                feedInstruction = feedInstruction,
                byLine = byLine,
                strategy = strategy,
                keepLast = keepLast,
                summarizeEvery = summarizeEvery,
                task = task,
                profile = profile,
                memoryMode = memoryMode,
                stageAgents = stageAgents,
                tui = ARG_TUI in values,
                judgeAgents = judgeAgents,
            )
        }

        /**
         * Parse the right-hand side of `-memory <…>`. Subcommands:
         *  - `show`
         *  - `profile <text>` — legacy free-text path (overwrite unnamed profile.md)
         *  - `profile <section> <text>` — append a bullet to a section of the
         *    unnamed profile (`style` | `format` | `constraints` | `context`)
         *  - `profile <section> clear` — empty one section of the unnamed profile
         *  - `profile clear` — drop the entire unnamed profile
         *  - `profile <name>` — touch-create a named profile
         *  - `profile <name> <section> <text>` — append a bullet to a section of a named profile
         *  - `profile <name> <section> clear` — empty one section of a named profile
         *  - `profile <name> clear` — delete a named profile
         *  - `profile-list` — list all named profiles
         *  - `profile-show <name>` — print one named profile's structure
         *  - `rule add <text>`
         *  - `rule rm <id>`
         *  - `task <id>` — create/select a task
         *  - `task <id> pause` / `task <id> resume` — pause/resume the task
         */
        private fun parseMemoryAction(raw: String): MemoryAction {
            val tokens = raw.trim().split(Regex("\\s+"), limit = 3)
            return when (tokens[0].lowercase()) {
                "show" -> {
                    if (tokens.size > 1) throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_MEMORY,
                        rawValue = raw,
                        expectedType = "$ARG_MEMORY show (no extra arguments)",
                    )
                    MemoryAction.Show
                }
                "profile" -> {
                    val body = raw.substringAfter(' ', missingDelimiterValue = "").trim()
                    parseMemoryProfileAction(body)
                }
                "profile-list" -> {
                    val tail = raw.substringAfter(' ', missingDelimiterValue = "").trim()
                    if (tail.isNotEmpty()) throw CliArgsException.InvalidArgumentValue(
                        argName = ARG_MEMORY,
                        rawValue = raw,
                        expectedType = "$ARG_MEMORY profile-list (no extra arguments)",
                    )
                    MemoryAction.ListProfiles
                }
                "profile-show" -> {
                    val name = raw.substringAfter(' ', missingDelimiterValue = "").trim()
                    if (name.isEmpty()) throw CliArgsException.MissingRequiredArgument(
                        argName = ARG_MEMORY,
                        detail = "profile-show needs a name",
                    )
                    validateProfileName(name)
                    MemoryAction.ShowProfile(name)
                }
                "rule" -> {
                    val verb = tokens.getOrNull(1)?.lowercase()
                    when (verb) {
                        "add" -> {
                            val text = tokens.getOrNull(2)?.trim().orEmpty()
                            if (text.isEmpty()) throw CliArgsException.MissingRequiredArgument(
                                argName = ARG_MEMORY,
                                detail = "rule add needs the new text",
                            )
                            MemoryAction.AddRule(text)
                        }
                        "rm" -> {
                            val id = tokens.getOrNull(2)?.trim().orEmpty()
                            if (id.isEmpty()) throw CliArgsException.MissingRequiredArgument(
                                argName = ARG_MEMORY,
                                detail = "rule rm needs an id",
                            )
                            MemoryAction.RemoveRule(id)
                        }
                        else -> throw CliArgsException.InvalidArgumentValue(
                            argName = ARG_MEMORY,
                            rawValue = raw,
                            expectedType = "rule add <text> or rule rm <id>",
                        )
                    }
                }
                "task" -> parseMemoryTaskAction(raw.substringAfter(' ', missingDelimiterValue = "").trim())
                else -> throw CliArgsException.InvalidArgumentValue(
                    argName = ARG_MEMORY,
                    rawValue = raw,
                    expectedType = "one of: show | profile [<text> | <section> <text> | <section> clear | clear | <name> [<section> [<text>|clear] | clear]] | profile-list | profile-show <name> | rule add <text> | rule rm <id> | task <id> [pause | resume]",
                )
            }
        }

        /**
         * Profile sub-grammar dispatch:
         *
         * - `clear` / `<section> <text>` / `<section> clear` — default
         *   unnamed profile path, via [parseProfileCommand].
         * - `<name>` (1 token, valid identifier) — touch-create named profile.
         * - `<name> clear` — drop named profile.
         * - `<name> <section> <text>` — append to named.
         * - `<name> <section> clear` — empty section in named.
         * - everything else — legacy free text into `profile.md`.
         */
        private fun parseMemoryProfileAction(body: String): MemoryAction {
            if (body.isEmpty()) throw CliArgsException.MissingRequiredArgument(
                argName = ARG_MEMORY,
                detail = "profile needs a section + text, a profile name, 'clear', or free-text",
            )

            // Default-profile path first.
            when (val parsed = parseProfileCommand(body)) {
                ProfileCommand.ClearAll -> return MemoryAction.ClearProfile
                is ProfileCommand.ClearSection -> return MemoryAction.ClearProfileSection(parsed.section)
                is ProfileCommand.Append -> {
                    if (parsed.text.isBlank()) throw CliArgsException.MissingRequiredArgument(
                        argName = ARG_MEMORY,
                        detail = "profile ${parsed.section.keyword} needs the new text",
                    )
                    return MemoryAction.AddProfileItem(parsed.section, parsed.text)
                }
                is ProfileCommand.SetFreeText -> Unit  // fall through to named-profile handling
                null -> throw CliArgsException.MissingRequiredArgument(
                    argName = ARG_MEMORY,
                    detail = "profile needs a section + text, a profile name, 'clear', or free-text",
                )
            }

            // The leading token isn't a section keyword — treat it as a
            // candidate profile name and look at what follows. Anything
            // that doesn't fit the named-profile shape falls back to the
            // legacy SetProfile (free-text) so old workflows keep working.
            val tokens = body.split(Regex("\\s+"))
            val name = tokens[0]
            if (!isValidProfileName(name)) return MemoryAction.SetProfile(body)

            if (tokens.size == 1) return MemoryAction.TouchProfile(name)

            val rest = tokens.drop(1)
            if (rest.size == 1 && rest[0].equals("clear", ignoreCase = true)) {
                return MemoryAction.ClearNamedProfile(name)
            }

            val section = ProfileSection.byKeyword(rest[0])
                ?: return MemoryAction.SetProfile(body)  // not a section → legacy free text

            if (rest.size == 1) throw CliArgsException.MissingRequiredArgument(
                argName = ARG_MEMORY,
                detail = "profile $name ${section.keyword} needs the new text",
            )
            if (rest.size == 2 && rest[1].equals("clear", ignoreCase = true)) {
                return MemoryAction.ClearNamedProfileSection(name, section)
            }
            // Drop the leading "<name> <section>" prefix from `body` and
            // pass the verbatim remainder as the new bullet.
            val text = body.substringAfter(' ').substringAfter(' ').trim()
            return MemoryAction.AddNamedProfileItem(name, section, text)
        }

        /**
         * Parse the right-hand side of `-memory task <…>`:
         *  - `<id>` — create/select the task (a new one starts at the initial stage)
         *  - `<id> pause` — pause the task (hold its stage)
         *  - `<id> resume` — resume the task
         *
         * Stage transitions themselves aren't a CLI verb: they advance
         * automatically from the model's reply during a chat. This standalone
         * op only covers creation and the pause/resume controls.
         */
        private fun parseMemoryTaskAction(body: String): MemoryAction {
            val tokens = body.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val id = tokens.getOrNull(0) ?: throw CliArgsException.MissingRequiredArgument(
                argName = ARG_MEMORY,
                detail = "task needs an id",
            )
            if (id.length > MAX_SESSION_NAME_LENGTH || !id.matches(SESSION_NAME_REGEX)) {
                throw CliArgsException.InvalidArgumentValue(
                    argName = ARG_MEMORY,
                    rawValue = id,
                    expectedType = "alphanumeric / '_' / '-', up to $MAX_SESSION_NAME_LENGTH chars",
                )
            }
            return when {
                tokens.size == 1 -> MemoryAction.SetTask(id)
                tokens.size == 2 && tokens[1].equals("pause", ignoreCase = true) -> MemoryAction.PauseTask(id)
                tokens.size == 2 && tokens[1].equals("resume", ignoreCase = true) -> MemoryAction.ResumeTask(id)
                else -> throw CliArgsException.InvalidArgumentValue(
                    argName = ARG_MEMORY,
                    rawValue = body,
                    expectedType = "task <id> [pause | resume]",
                )
            }
        }

        private fun isValidProfileName(s: String): Boolean =
            s.matches(SESSION_NAME_REGEX) && s.length <= MAX_SESSION_NAME_LENGTH

        private fun validateProfileName(s: String) {
            if (!isValidProfileName(s)) throw CliArgsException.InvalidArgumentValue(
                argName = ARG_MEMORY,
                rawValue = s,
                expectedType = "alphanumeric / '_' / '-', up to $MAX_SESSION_NAME_LENGTH chars",
            )
        }

        private fun buildModelProvider(
            providerRaw: String,
            modelRaw: String?,
            geminiApiKey: String,
            openRouterApiKey: String,
            huggingFaceApiKey: String,
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
            PROVIDER_HUGGINGFACE -> {
                if (huggingFaceApiKey.isBlank()) {
                    throw CliArgsException.MissingRequiredArgument(
                        argName = "HUGGINGFACE_API_KEY",
                        detail = "set HUGGINGFACE_API_KEY in local.properties or as an env var",
                    )
                }
                ModelProvider.HuggingFace(
                    model = modelRaw?.let(HuggingFaceModel.Companion::fromId) ?: HuggingFaceModel.Default,
                    apiKey = huggingFaceApiKey,
                )
            }
            else -> throw CliArgsException.InvalidArgumentValue(
                argName = ARG_PROVIDER,
                rawValue = providerRaw,
                expectedType = "one of: $PROVIDER_GEMINI, $PROVIDER_OPENROUTER, $PROVIDER_HUGGINGFACE",
            )
        }

        /**
         * Parse one `-stageAgent <from..to>=<provider>:<model>[@<profile>]`.
         * Model ids carry `/` and `:` but never `=` or `@`, so: split the range
         * off at the first `=`, peel an optional `@profile` off the end, then
         * take the provider up to the first `:` and the model as the rest
         * (inner colons stay in the model id).
         */
        private fun parseStageAgentSpec(
            raw: String,
            geminiApiKey: String,
            openRouterApiKey: String,
            huggingFaceApiKey: String,
        ): StageAgentSpec {
            val expected = "<from..to>=<provider>:<model>[@<profile>]"
            fun bad(detail: String): Nothing = throw CliArgsException.InvalidArgumentValue(
                argName = ARG_STAGE_AGENT, rawValue = raw, expectedType = detail,
            )

            val eq = raw.indexOf('=')
            if (eq <= 0 || eq >= raw.length - 1) bad(expected)
            val rangeRaw = raw.substring(0, eq)
            var spec = raw.substring(eq + 1)

            // Optional @profile suffix — model ids never contain '@'.
            var profileName: String? = null
            val at = spec.indexOf('@')
            if (at >= 0) {
                val p = spec.substring(at + 1)
                if (!isValidProfileName(p)) {
                    bad("profile after '@' must be alphanumeric / '_' / '-', up to $MAX_SESSION_NAME_LENGTH chars")
                }
                profileName = p
                spec = spec.substring(0, at)
            }

            // provider:model — provider up to the first ':', model is the rest.
            val colon = spec.indexOf(':')
            if (colon <= 0 || colon >= spec.length - 1) bad(expected)
            val providerRaw = spec.substring(0, colon)
            val modelRaw = spec.substring(colon + 1)

            // from..to (single stage when there is no "..").
            val parts = rangeRaw.split("..")
            if (parts.size > 2) bad("stage range must be <stage> or <from>..<to>")
            val from = TaskStage.byKeyword(parts.first()) ?: bad("unknown stage '${parts.first()}'")
            val to = if (parts.size == 1) from else TaskStage.byKeyword(parts[1]) ?: bad("unknown stage '${parts[1]}'")
            if (from.ordinal > to.ordinal) bad("stage range from must not be after to: $rangeRaw")

            val provider = buildModelProvider(providerRaw, modelRaw, geminiApiKey, openRouterApiKey, huggingFaceApiKey)
            return StageAgentSpec(TaskBinding(from, to), provider, profileName)
        }

        /**
         * Parse one `-judgeAgent <from..to>=<provider>:<model>` into a
         * [StageJudgeSpec]. Same `<from..to>=<provider>:<model>` grammar as
         * [parseStageAgentSpec] minus the `@profile` suffix — a judge has no
         * persona. Inner colons stay in the model id.
         */
        private fun parseJudgeAgentSpec(
            raw: String,
            geminiApiKey: String,
            openRouterApiKey: String,
            huggingFaceApiKey: String,
        ): StageJudgeSpec {
            val expected = "<from..to>=<provider>:<model>"
            fun bad(detail: String): Nothing = throw CliArgsException.InvalidArgumentValue(
                argName = ARG_JUDGE_AGENT, rawValue = raw, expectedType = detail,
            )

            val eq = raw.indexOf('=')
            if (eq <= 0 || eq >= raw.length - 1) bad(expected)
            val rangeRaw = raw.substring(0, eq)
            val spec = raw.substring(eq + 1)
            // A judge has no persona — reject a stray @profile rather than
            // folding it into the model id.
            if ('@' in spec) bad("judge takes no @profile — use $expected")

            // provider:model — provider up to the first ':', model is the rest.
            val colon = spec.indexOf(':')
            if (colon <= 0 || colon >= spec.length - 1) bad(expected)
            val providerRaw = spec.substring(0, colon)
            val modelRaw = spec.substring(colon + 1)

            // from..to (single stage when there is no "..").
            val parts = rangeRaw.split("..")
            if (parts.size > 2) bad("stage range must be <stage> or <from>..<to>")
            val from = TaskStage.byKeyword(parts.first()) ?: bad("unknown stage '${parts.first()}'")
            val to = if (parts.size == 1) from else TaskStage.byKeyword(parts[1]) ?: bad("unknown stage '${parts[1]}'")
            if (from.ordinal > to.ordinal) bad("stage range from must not be after to: $rangeRaw")

            val provider = buildModelProvider(providerRaw, modelRaw, geminiApiKey, openRouterApiKey, huggingFaceApiKey)
            return StageJudgeSpec(TaskBinding(from, to), provider)
        }
    }
}

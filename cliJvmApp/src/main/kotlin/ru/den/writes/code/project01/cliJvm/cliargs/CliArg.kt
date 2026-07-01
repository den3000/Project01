package ru.den.writes.code.project01.cliJvm.cliargs

/**
 * A unified description of the CLI's controls (startup flags AND in-session
 * commands): one declarative catalog drives both fronts (`-flag` and `/cmd`),
 * replacing the two hand-rolled parsers that used to do it. The runtime parses
 * against this catalog. See README.md.
 *
 * Where a control token may appear. A control's allowed surfaces encode its kind:
 * `{FLAG}` = startup-only, `{CMD}` = in-session only, `{FLAG, CMD}` = flag-command
 * (both), `{SUB}` = a positional subcommand under a parent (no prefix).
 */
enum class Surface(val prefix: String) {
    FLAG("-"),
    CMD("/"),
    SUB(""),
}

/**
 * The whole token vocabulary — every word the grammar recognises, with its literal
 * [title]. One token = one entry; the same token can play several roles (e.g.
 * `profile` is a top-level entity AND a sub of `agent`) — the [ArgSpec.parent]
 * chain disambiguates, so the title stays unique here.
 */
enum class CliArg(val title: String) {
    // startup-only flags
    PROMPT("prompt"),
    ONESHOT("oneshot"),
    TUI("tui"),
    FEED_FILE("feedFile"),
    CHUNK_CHARS("chunkChars"),
    BY_LINE("byLine"),
    FEED_INSTRUCTION("feedInstruction"),

    // command-only
    REUSE("reuse"),
    EXIT("exit"),
    HELP("help"),
    MEMORY("memory"),

    // entities
    SESSION("session"),
    PROFILE("profile"),
    TASK("task"),
    RULE("rule"),
    BRANCH("branch"),
    AGENT("agent"),

    // shared entity operations
    SHOW("show"),
    CLEAR("clear"),
    SWITCH("switch"),

    // task operations
    PAUSE("pause"),
    RESUME("resume"),
    NOTE("note"),

    // profile sections
    STYLE("style"),
    FORMAT("format"),
    CONSTRAINTS("constraints"),
    CONTEXT("context"),

    // agent configuration sub-options
    PROVIDER("provider"),
    MODEL("model"),
    MAX_TOKENS("maxTokens"),
    TEMPERATURE("temperature"),
    STOP_SEQUENCE("stopSequence"),
    END_SEQUENCE("endSequence"),
    MODE("mode"),
    STAGES("stages"),
    JUDGE("judge"),

    // standalone config flag-commands
    STRATEGY("strategy"),
    KEEP_LAST("keepLast"),
    SUMMARIZE_EVERY("summarizeEvery"),
    INFLATE("inflate"),

    // tools
    MCP_SERVER("mcpServer"),

    // scheduling
    SCHEDULE("schedule"),
    TOOL("tool"),
    ARGS("args"),
    AFTER("after"),
    EVERY("every"),
    ;

    companion object {
        private val byTitle: Map<String, CliArg> = entries.associateBy { it.title }

        /** The arg whose [title] equals [token], or null if it isn't a known word. */
        fun of(token: String): CliArg? = byTitle[token]
    }
}

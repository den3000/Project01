package ru.den.writes.code.agenticHub.cliJvm.cliargs

import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.AFTER
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.AGENT
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.ARGS
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.BRANCH
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.BY_LINE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.CHUNK_CHARS
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.CLEAR
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.CONSTRAINTS
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.CONTEXT
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.END_SEQUENCE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.EVERY
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.EXIT
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.FEED_FILE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.FEED_INSTRUCTION
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.FORMAT
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.HELP
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.INFLATE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.JUDGE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.KEEP_LAST
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.MAX_TOKENS
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.MCP_SERVER
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.MEMORY
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.MODE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.MODEL
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.NOTE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.ONESHOT
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.PAUSE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.PROFILE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.PROMPT
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.PROVIDER
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.RESUME
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.REUSE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.RULE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.SCHEDULE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.SESSION
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.SHOW
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.STAGES
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.STOP_SEQUENCE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.STRATEGY
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.STYLE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.SUMMARIZE_EVERY
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.SWITCH
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.TASK
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.TEMPERATURE
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.TOOL
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArg.TUI
import ru.den.writes.code.agenticHub.cliJvm.cliargs.Surface.CMD
import ru.den.writes.code.agenticHub.cliJvm.cliargs.Surface.FLAG
import ru.den.writes.code.agenticHub.cliJvm.cliargs.Surface.SUB

/**
 * The control catalog — ONE declarative list that both fronts (`-flag` and
 * `/cmd`) parse against. Entities are declared once via [entity] and auto-expand
 * to the standard `select / list / show / clean` ops; entity-specific extras are
 * added explicitly. This is the "centralization" the redesign is after: grammar,
 * surfaces, value types and cross-constraints live here as data, not as branches
 * scattered across hand-rolled parsers.
 */
internal object CliArgs {

    val all: List<ArgSpec> = buildCatalog()

    private val topByArg: Map<CliArg, List<ArgSpec>> =
        all.filter { it.isTopLevel }.groupBy { it.arg }
    private val subsByParent: Map<List<CliArg>, List<ArgSpec>> =
        all.filter { !it.isTopLevel }.groupBy { it.parent!! }

    /** The top-level descriptor for [arg] usable on [surface], or null. */
    fun topLevel(arg: CliArg, surface: Surface): ArgSpec? =
        topByArg[arg]?.firstOrNull { surface in it.surfaces }

    /** The descriptor for [token] as a sub of [chain] (exact ancestor match), or null. */
    fun subOf(chain: List<CliArg>, token: String): ArgSpec? {
        val arg = CliArg.of(token) ?: return null
        return subsByParent[chain]?.firstOrNull { it.arg == arg }
    }
}

// ---- catalog construction --------------------------------------------------

private fun req(kind: ValueKind) = ValueSpec(kind, required = true)
private fun opt(kind: ValueKind) = ValueSpec(kind, required = false)

private fun top(
    arg: CliArg,
    surfaces: Set<Surface>,
    value: ValueSpec? = null,
    valueSurfaces: Set<Surface> = surfaces,
    requires: Set<CliArg> = emptySet(),
    excludes: Set<CliArg> = emptySet(),
    usage: String = "",
) = ArgSpec(arg, surfaces, parent = null, value, valueSurfaces, requires, excludes, usage = usage)

private fun sub(
    arg: CliArg,
    parent: List<CliArg>,
    value: ValueSpec? = null,
    requires: Set<CliArg> = emptySet(),
    excludes: Set<CliArg> = emptySet(),
    parentValueIn: Set<String>? = null,
    usage: String = "",
) = ArgSpec(arg, setOf(SUB), parent, value, valueSurfaces = setOf(SUB), requires = requires, excludes = excludes, parentValueIn = parentValueIn, usage = usage)

/**
 * Declare an entity once: it gets `<entity> [<value>]` (value present = select /
 * create / add, absent = list), `<entity> show [<value>]` and `<entity> clean
 * [<value>]`, plus any [extras]. [selectSurfaces] lets a value be allowed on
 * fewer surfaces than the token (session: name only at startup); [topExcludes]
 * are conflicts placed on the top control only (not its show/clean subs).
 */
private fun entity(
    arg: CliArg,
    surfaces: Set<Surface>,
    selectValue: ValueKind = ValueKind.Name,
    selectSurfaces: Set<Surface> = surfaces,
    topExcludes: Set<CliArg> = emptySet(),
    extras: List<ArgSpec> = emptyList(),
): List<ArgSpec> = buildList {
    add(top(arg, surfaces, value = opt(selectValue), valueSurfaces = selectSurfaces, excludes = topExcludes, usage = "<name> select/create · bare = list"))
    add(sub(SHOW, listOf(arg), value = opt(selectValue), usage = "show one (<name>) or all"))
    add(sub(CLEAR, listOf(arg), value = opt(selectValue), usage = "delete one (<name>) or all"))
    addAll(extras)
}

private fun buildCatalog(): List<ArgSpec> = buildList {
    // ---- startup-only flags ----
    add(top(PROMPT, setOf(FLAG), value = req(ValueKind.Text), usage = "opening prompt — every run starts here"))
    add(top(ONESHOT, setOf(FLAG), usage = "single prompt → reply → exit (no REPL/session/feed)"))
    add(top(TUI, setOf(FLAG), excludes = setOf(ONESHOT), usage = "interactive TUI view (needs a real TTY)"))
    add(top(FEED_FILE, setOf(FLAG), value = req(ValueKind.Path), excludes = setOf(ONESHOT), usage = "after the opening prompt, feed this file turn by turn"))
    add(sub(CHUNK_CHARS, listOf(FEED_FILE), value = req(ValueKind.IntRange(1)), excludes = setOf(BY_LINE), usage = "feed chunk size, chars"))
    add(sub(BY_LINE, listOf(FEED_FILE), excludes = setOf(CHUNK_CHARS), usage = "feed line by line"))
    add(sub(FEED_INSTRUCTION, listOf(FEED_FILE), value = req(ValueKind.Text), usage = "prefix prepended to each fed chunk"))

    // ---- command-only ----
    add(top(REUSE, setOf(CMD), usage = "resend the last model reply"))
    add(top(EXIT, setOf(CMD), usage = "leave the session"))
    add(top(HELP, setOf(CMD), usage = "open the command palette"))
    add(top(MEMORY, setOf(FLAG, CMD), excludes = setOf(ONESHOT), usage = "show the active memory layer (mode + profile + rules + task)"))
    // memory-injection mode is flipped via `agent mode <preamble|system>` (agent sub), not a top-level control.

    // ---- entities (auto CRUD + extras) ----
    addAll(entity(SESSION, setOf(FLAG, CMD), selectSurfaces = setOf(FLAG), topExcludes = setOf(ONESHOT)))  // select only at startup
    addAll(entity(PROFILE, setOf(FLAG, CMD), topExcludes = setOf(ONESHOT), extras = profileSections()))
    addAll(
        entity(
            TASK, setOf(FLAG, CMD),
            topExcludes = setOf(ONESHOT),
            extras = listOf(
                sub(PAUSE, listOf(TASK), usage = "hold the active task's stage"),
                sub(RESUME, listOf(TASK), usage = "resume the task"),
                sub(NOTE, listOf(TASK), value = req(ValueKind.Text), usage = "append a note"),
            ),
        ),
    )
    // rule: value present = ADD (not select); no "active" rule — differs only in meaning, not grammar.
    // Deletion is the uniform `rule clear [<id>]` (entity-protocol verb), no rule-only `rm`.
    addAll(entity(RULE, setOf(FLAG, CMD), selectValue = ValueKind.Text, topExcludes = setOf(ONESHOT)))
    addAll(
        entity(
            BRANCH, setOf(CMD),  // command-only; `branch show` = current branch + count, bare = list
            extras = listOf(
                sub(SWITCH, listOf(BRANCH), value = req(ValueKind.Name), usage = "switch to an existing branch"),
            ),
        ),
    )
    addAll(agentEntity())

    // ---- standalone config flag-commands ----
    add(top(STRATEGY, setOf(FLAG, CMD), value = req(ValueKind.OneOf(setOf("full", "window", "facts", "summary"))), excludes = setOf(ONESHOT), usage = "context-size management"))
    add(sub(KEEP_LAST, listOf(STRATEGY), value = req(ValueKind.IntRange(0)), usage = "verbatim tail size (window/summary)"))
    add(sub(SUMMARIZE_EVERY, listOf(STRATEGY), value = req(ValueKind.IntRange(2)), parentValueIn = setOf("summary"), usage = "fold threshold (summary)"))
    add(top(INFLATE, setOf(FLAG, CMD), value = req(ValueKind.IntRange(1)), requires = setOf(SESSION), excludes = setOf(ONESHOT), usage = "duplicate the last N rows of the session (dev)"))

    // ---- tools (MCP) ----
    // -mcpServer is startup-only (Chat-only) and repeatable: pass it once per server, and the
    // model gets the union of their tools (calls routed to the owning server by tool name).
    add(
        top(
            MCP_SERVER, setOf(FLAG, CMD), value = req(ValueKind.Text), excludes = setOf(ONESHOT),
            usage = "spawn an MCP server and offer its tools to the model (repeatable, one per server)",
        ),
    )

    // ---- scheduling ----
    addAll(scheduleControls())
}

/**
 * `schedule <collect|agent> …` — a repeatable task spec. `collect tool <name> [args "<json>"]`
 * calls an MCP tool on the schedule (needs `-mcpServer`); `agent prompt "<text>"` runs a turn.
 * Exactly one of `after <sec>` (one-shot) / `every <sec>` (periodic) sets the timing.
 */
private fun scheduleControls(): List<ArgSpec> = listOf(
    top(SCHEDULE, setOf(FLAG, CMD), value = opt(ValueKind.OneOf(setOf("collect", "agent"))), excludes = setOf(ONESHOT), usage = "collect <tool> | agent <prompt> + after/every <sec>; bare = list, clear [<id>] = cancel"),
    sub(CLEAR, listOf(SCHEDULE), value = opt(ValueKind.Name), usage = "cancel one task (<id>) or all active (bare)"),
    sub(TOOL, listOf(SCHEDULE), value = req(ValueKind.Name), requires = setOf(MCP_SERVER), parentValueIn = setOf("collect"), usage = "MCP tool to call (collect; needs -mcpServer)"),
    sub(ARGS, listOf(SCHEDULE), value = req(ValueKind.Text), parentValueIn = setOf("collect"), usage = "JSON args for the tool (collect)"),
    sub(PROMPT, listOf(SCHEDULE), value = req(ValueKind.Text), parentValueIn = setOf("agent"), usage = "prompt to run (agent)"),
    sub(AFTER, listOf(SCHEDULE), value = req(ValueKind.IntRange(1)), usage = "fire once, N seconds from now"),
    sub(EVERY, listOf(SCHEDULE), value = req(ValueKind.IntRange(1)), usage = "fire every N seconds"),
)

private fun profileSections(): List<ArgSpec> = listOf(STYLE, FORMAT, CONSTRAINTS, CONTEXT).map { section ->
    // `profile <name> <section> <text>` appends; `<section> clean` empties it — modelled as the
    // section taking optional text (absent = clear). Kept flat for the prototype.
    sub(section, listOf(PROFILE), value = opt(ValueKind.Text), usage = "append a bullet (or clear)")
}

private fun agentEntity(): List<ArgSpec> = entity(
    AGENT, setOf(FLAG, CMD),
    extras = listOf(
        sub(PROVIDER, listOf(AGENT), value = req(ValueKind.OneOf(setOf("gemini", "openrouter", "huggingface"))), usage = "llm provider"),
        sub(MODEL, listOf(AGENT), value = req(ValueKind.Text), usage = "model id"),
        sub(MAX_TOKENS, listOf(AGENT), value = req(ValueKind.IntRange(1)), usage = "output cap"),
        sub(TEMPERATURE, listOf(AGENT), value = req(ValueKind.Decimal(0.0, 2.0)), usage = "sampling temperature"),
        sub(STOP_SEQUENCE, listOf(AGENT), value = req(ValueKind.Text), usage = "stop sequence"),
        sub(END_SEQUENCE, listOf(AGENT), value = req(ValueKind.Text), usage = "end sequence"),
        sub(PROFILE, listOf(AGENT), value = req(ValueKind.Name), usage = "bind a profile to this agent"),
        sub(MODE, listOf(AGENT), value = req(ValueKind.OneOf(setOf("none", "system", "preamble"))), excludes = setOf(ONESHOT), usage = "context-delivery mode"),
        sub(STAGES, listOf(AGENT), value = req(ValueKind.StageRange), excludes = setOf(ONESHOT), usage = "bind to a task-stage range"),
        sub(JUDGE, listOf(AGENT), excludes = setOf(ONESHOT), usage = "this agent is a rules judge (no profile)"),
    ),
)

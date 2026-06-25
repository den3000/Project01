package ru.den.writes.code.project01.cliJvm.clicontrols

import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.AGENT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.BRANCH
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.BY_LINE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CHECK
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CHUNK_CHARS
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CLEAN
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CONSTRAINTS
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CONTEXT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.END_SEQUENCE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.EXIT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.FEED_FILE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.FEED_INSTRUCTION
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.FORMAT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.HELP
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.INFLATE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.JUDGE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.KEEP_LAST
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MAX_TOKENS
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MCP_SERVER
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MODE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MODEL
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.NOTE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.ONESHOT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PAUSE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PROFILE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PROMPT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PROVIDER
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.RESUME
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.REUSE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.RM
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.RULE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SESSION
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SHOW
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STAGES
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STOP_SEQUENCE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STRATEGY
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STYLE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SUMMARIZE_EVERY
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.TASK
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.TEMPERATURE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.TUI
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.CMD
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.FLAG
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.SUB

/**
 * The control catalog — ONE declarative list that both fronts (`-flag` and
 * `/cmd`) parse against. Entities are declared once via [entity] and auto-expand
 * to the standard `select / list / show / clean` ops; entity-specific extras are
 * added explicitly. This is the "centralization" the redesign is after: grammar,
 * surfaces, value types and cross-constraints live here as data, not as branches
 * spread across `CliArgs.from`.
 */
internal object CliControls {

    val all: List<ControlSpec> = buildCatalog()

    private val topByArg: Map<CliControlsArg, List<ControlSpec>> =
        all.filter { it.isTopLevel }.groupBy { it.arg }
    private val subsByParent: Map<List<CliControlsArg>, List<ControlSpec>> =
        all.filter { !it.isTopLevel }.groupBy { it.parent!! }

    /** The top-level descriptor for [arg] usable on [surface], or null. */
    fun topLevel(arg: CliControlsArg, surface: Surface): ControlSpec? =
        topByArg[arg]?.firstOrNull { surface in it.surfaces }

    /** The descriptor for [token] as a sub of [chain] (exact ancestor match), or null. */
    fun subOf(chain: List<CliControlsArg>, token: String): ControlSpec? {
        val arg = CliControlsArg.of(token) ?: return null
        return subsByParent[chain]?.firstOrNull { it.arg == arg }
    }
}

// ---- catalog construction --------------------------------------------------

private fun req(kind: ValueKind) = ValueSpec(kind, required = true)
private fun opt(kind: ValueKind) = ValueSpec(kind, required = false)

private fun top(
    arg: CliControlsArg,
    surfaces: Set<Surface>,
    value: ValueSpec? = null,
    valueSurfaces: Set<Surface> = surfaces,
    requires: Set<CliControlsArg> = emptySet(),
    excludes: Set<CliControlsArg> = emptySet(),
    usage: String = "",
) = ControlSpec(arg, surfaces, parent = null, value, valueSurfaces, requires, excludes, usage = usage)

private fun sub(
    arg: CliControlsArg,
    parent: List<CliControlsArg>,
    value: ValueSpec? = null,
    excludes: Set<CliControlsArg> = emptySet(),
    parentValueIn: Set<String>? = null,
    usage: String = "",
) = ControlSpec(arg, setOf(SUB), parent, value, valueSurfaces = setOf(SUB), excludes = excludes, parentValueIn = parentValueIn, usage = usage)

/**
 * Declare an entity once: it gets `<entity> [<value>]` (value present = select /
 * create / add, absent = list), `<entity> show [<value>]` and `<entity> clean
 * [<value>]`, plus any [extras]. [selectSurfaces] lets a value be allowed on
 * fewer surfaces than the token (session: name only at startup); [topExcludes]
 * are conflicts placed on the top control only (not its show/clean subs).
 */
private fun entity(
    arg: CliControlsArg,
    surfaces: Set<Surface>,
    selectValue: ValueKind = ValueKind.Name,
    selectSurfaces: Set<Surface> = surfaces,
    topExcludes: Set<CliControlsArg> = emptySet(),
    extras: List<ControlSpec> = emptyList(),
): List<ControlSpec> = buildList {
    add(top(arg, surfaces, value = opt(selectValue), valueSurfaces = selectSurfaces, excludes = topExcludes, usage = "<name> select/create · bare = list"))
    add(sub(SHOW, listOf(arg), value = opt(selectValue), usage = "show one (<name>) or all"))
    add(sub(CLEAN, listOf(arg), value = opt(selectValue), usage = "delete one (<name>) or all; reset selection"))
    addAll(extras)
}

private fun buildCatalog(): List<ControlSpec> = buildList {
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
    addAll(
        entity(
            RULE, setOf(FLAG, CMD), selectValue = ValueKind.Text, topExcludes = setOf(ONESHOT),
            extras = listOf(sub(RM, listOf(RULE), value = req(ValueKind.Name), usage = "remove a rule by id")),
        ),
    )
    addAll(
        entity(
            BRANCH, setOf(CMD),  // command-only
            extras = listOf(sub(CHECK, listOf(BRANCH), usage = "current branch + message count (old /checkpoint)")),
        ),
    )
    addAll(agentEntity())

    // ---- standalone config flag-commands ----
    add(top(STRATEGY, setOf(FLAG, CMD), value = req(ValueKind.OneOf(setOf("full", "window", "facts", "summary"))), excludes = setOf(ONESHOT), usage = "context-size management"))
    add(sub(KEEP_LAST, listOf(STRATEGY), value = req(ValueKind.IntRange(0)), usage = "verbatim tail size (window/summary)"))
    add(sub(SUMMARIZE_EVERY, listOf(STRATEGY), value = req(ValueKind.IntRange(2)), parentValueIn = setOf("summary"), usage = "fold threshold (summary)"))
    add(top(INFLATE, setOf(FLAG, CMD), value = req(ValueKind.IntRange(1)), requires = setOf(SESSION), excludes = setOf(ONESHOT), usage = "duplicate the last N rows of the session (dev)"))

    // ---- tools (MCP) ----
    // Merged feature ships -mcpServer startup-only (Chat-only); modelled here as a flag-command,
    // since attaching a tool server mid-session is a natural extension. See README "open questions"
    // on whether tools belong per-agent (`agent <name> mcp …`) in the agent-as-entity model.
    add(
        top(
            MCP_SERVER, setOf(FLAG, CMD), value = req(ValueKind.Text), excludes = setOf(ONESHOT),
            usage = "spawn an MCP server (e.g. \"mcpLab --serve\") and offer its tools to the model",
        ),
    )
}

private fun profileSections(): List<ControlSpec> = listOf(STYLE, FORMAT, CONSTRAINTS, CONTEXT).map { section ->
    // `profile <name> <section> <text>` appends; `<section> clean` empties it — modelled as the
    // section taking optional text (absent = clear). Kept flat for the prototype.
    sub(section, listOf(PROFILE), value = opt(ValueKind.Text), usage = "append a bullet (or clear)")
}

private fun agentEntity(): List<ControlSpec> = entity(
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

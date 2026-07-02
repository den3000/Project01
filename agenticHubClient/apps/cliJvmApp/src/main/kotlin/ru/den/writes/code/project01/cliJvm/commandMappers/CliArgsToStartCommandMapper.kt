package ru.den.writes.code.project01.cliJvm.commandMappers

import ru.den.writes.code.project01.cliJvm.ContextStrategyKind
import ru.den.writes.code.project01.cliJvm.ModelProviderFactory
import ru.den.writes.code.agenticHub.features.llm.MAX_STOP_SEQUENCES
import ru.den.writes.code.project01.cliJvm.StageAgentSpec
import ru.den.writes.code.project01.cliJvm.StageJudgeSpec
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg
import ru.den.writes.code.project01.cliJvm.cliargs.CliArgsParser
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.AFTER
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.AGENT
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.ARGS
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.BY_LINE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.CHUNK_CHARS
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.CLEAR
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.END_SEQUENCE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.EVERY
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.FEED_FILE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.FEED_INSTRUCTION
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.INFLATE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.JUDGE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.KEEP_LAST
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.MAX_TOKENS
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.MCP_SERVER
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.MEMORY
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.MODE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.ONESHOT
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.PROFILE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.PROMPT
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.SESSION
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.STAGES
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.STOP_SEQUENCE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.STRATEGY
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.SUMMARIZE_EVERY
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.TASK
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.TEMPERATURE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.CONSTRAINTS
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.CONTEXT
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.FORMAT
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.NOTE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.PAUSE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.RESUME
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.RULE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.SCHEDULE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.SHOW
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.STYLE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.TOOL
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.TUI
import ru.den.writes.code.project01.cliJvm.cliargs.ParsedArg
import ru.den.writes.code.project01.cliJvm.cliargs.has
import ru.den.writes.code.project01.cliJvm.cliargs.last
import ru.den.writes.code.project01.cliJvm.cliargs.subValue
import ru.den.writes.code.project01.cliJvm.cliargs.ParseError
import ru.den.writes.code.project01.cliJvm.command.MemoryAction
import ru.den.writes.code.project01.cliJvm.command.ScheduleSpec
import ru.den.writes.code.project01.cliJvm.command.SessionConfig
import ru.den.writes.code.project01.cliJvm.command.StartCommand
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.ProfileSection
import ru.den.writes.code.project01.shared.memory.TaskBinding
import ru.den.writes.code.project01.shared.memory.TaskStage

/**
 * The outcome of [CliArgsToStartCommandMapper.parse]: a mapped [StartCommand], or a
 * [ParseError] describing the first rejection (rendered by `main`).
 */
internal sealed interface ParsedStartCommand {
    data class Ok(val command: StartCommand) : ParsedStartCommand
    data class Err(val error: ParseError) : ParsedStartCommand
}

/**
 * Early-exit carrier: a deep post-parse check throws this; [CliArgsToStartCommandMapper.parse]
 * catches it and turns it into [ParsedStartCommand.Err]. Never escapes the mapper.
 */
internal class MapBail(val error: ParseError) : RuntimeException()

/** Bail out of mapping with a post-parse semantic [ParseError]. */
internal fun bailMissing(argName: String, detail: String? = null): Nothing =
    throw MapBail(ParseError.MissingRequired(argName, detail))

internal fun bailInvalid(argName: String, rawValue: String, expectedType: String): Nothing =
    throw MapBail(ParseError.Invalid(argName, rawValue, expectedType))

internal fun bailTooMany(argName: String, count: Int, maxAllowed: Int): Nothing =
    throw MapBail(ParseError.TooManyValues(argName, count, maxAllowed))

/**
 * The runtime arg front: parse args with the cliargs grammar and map the top-level
 * [ParsedArg]s straight onto a domain [ru.den.writes.code.project01.cliJvm.command.StartCommand]. The grammar bundles
 * provider/model/knobs/stages/judge under `agent`, so a single agent without
 * stages/judge is the "primary" (default agent); agents with `stages` become stage
 * agents, with `judge` become judges. Provider resolution (and the API keys) is
 * delegated to [modelProviderFactory]; productions with no domain target throw an
 * `InvalidArgumentValue` "not expressible …" (see [gap]).
 */
internal class CliArgsToStartCommandMapper(
    private val parser: CliArgsParser,
    private val modelProviderFactory: ModelProviderFactory,
) {

    /**
     * Parse [args] with the cliargs grammar and map them onto a [StartCommand], or
     * return a [ParseError] describing the first rejection (`main` renders it +
     * USAGE for the [ParseError.MissingArg] family).
     */
    fun parse(args: Array<String>): ParsedStartCommand {
        val batch = parser.parseArgv(args.toList())
        batch.errors.firstOrNull()?.let { return ParsedStartCommand.Err(it) }
        return try {
            ParsedStartCommand.Ok(map(batch.controls))
        } catch (b: MapBail) {
            ParsedStartCommand.Err(b.error)
        }
    }

    private fun map(controls: List<ParsedArg>): StartCommand {
        val prompt = controls.last(PROMPT)
        return if (prompt != null) promptCommand(controls, prompt) else adminCommand(controls)
    }

    // ---- prompt modes -----------------------------------------------------

    private fun promptCommand(controls: List<ParsedArg>, prompt: ParsedArg): StartCommand {
        val agents = controls.filter { it.arg == AGENT }
        val primaries = agents.filter { it.sub(JUDGE) == null && it.sub(STAGES) == null }
        if (primaries.size > 1) {
            bailInvalid("-agent", "(multiple)", "exactly one agent without stages/judge")
        }
        val primary = primaries.singleOrNull()

        if (controls.has(ONESHOT)) {
            // The catalog already bars session/feed/strategy/tui/mcp/mode/stages/judge with oneshot.
            return StartCommand.RunOneShot(
                prompt = prompt.value!!,
                maxTokens = primary?.subValue(MAX_TOKENS)?.toInt(),
                stopSequences = stopSequences(primary),
                endSequence = primary?.subValue(END_SEQUENCE),
                temperature = primary?.subValue(TEMPERATURE)?.toDouble(),
                modelProvider = modelProviderFactory.buildProvider(primary),
            )
        }

        val stageAgents = agents.filter { it.sub(JUDGE) == null && it.sub(STAGES) != null }.map { stageSpec(it) }
        val judgeAgents = agents.filter { it.sub(JUDGE) != null }.map { judgeSpec(it) }
        val memoryMode = memoryMode(primary?.subValue(MODE))
        if (stageAgents.isNotEmpty() && memoryMode == null) {
            bailInvalid("-agent", "stages", "stage agents need a memory mode")
        }
        if (judgeAgents.isNotEmpty() && stageAgents.isEmpty()) {
            bailInvalid("-agent", "judge", "a judge needs a stage agent")
        }
        val feed = controls.last(FEED_FILE)
        val strategy = controls.last(STRATEGY)
        return StartCommand.RunChat(
            prompt = prompt.value!!,
            maxTokens = primary?.subValue(MAX_TOKENS)?.toInt(),
            stopSequences = stopSequences(primary),
            endSequence = primary?.subValue(END_SEQUENCE),
            temperature = primary?.subValue(TEMPERATURE)?.toDouble(),
            modelProvider = modelProviderFactory.buildProvider(primary),
            config = SessionConfig(
                session = controls.last(SESSION)?.value,
                feedFile = feed?.value,
                chunkChars = feed?.subValue(CHUNK_CHARS)?.toInt() ?: DEFAULT_CHUNK_CHARS,
                feedInstruction = feed?.subValue(FEED_INSTRUCTION) ?: "",
                byLine = feed?.sub(BY_LINE) != null,
                strategy = strategyKind(strategy?.value),
                keepLast = strategy?.subValue(KEEP_LAST)?.toInt() ?: DEFAULT_KEEP_LAST,
                summarizeEvery = strategy?.subValue(SUMMARIZE_EVERY)?.toInt()
                    ?: DEFAULT_SUMMARIZE_EVERY,
                task = controls.last(TASK)?.value,
                profile = primary?.subValue(PROFILE),
                memoryMode = memoryMode,
                stageAgents = stageAgents,
                tui = controls.has(TUI),
                judgeAgents = judgeAgents,
                mcpServers = controls.filter { it.arg == MCP_SERVER }.mapNotNull { it.value },
                schedules = scheduleSpecs(controls),
            ),
        )
    }

    private fun stageSpec(agent: ParsedArg): StageAgentSpec =
        StageAgentSpec(stageBinding(agent.subValue(STAGES)!!), modelProviderFactory.buildProvider(agent), agent.subValue(PROFILE))

    private fun judgeSpec(agent: ParsedArg): StageJudgeSpec {
        if (agent.sub(STAGES) == null) {
            bailInvalid("-agent", "judge", "a judge needs a stage span")
        }
        if (agent.sub(PROFILE) != null) {
            bailInvalid("-agent", "judge", "a judge takes no profile")
        }
        return StageJudgeSpec(stageBinding(agent.subValue(STAGES)!!), modelProviderFactory.buildProvider(agent))
    }

    /** Both ends are already validated (known stages, from ≤ to) by the catalog's StageRange. */
    private fun stageBinding(raw: String): TaskBinding {
        val parts = raw.split("..")
        val from = TaskStage.byKeyword(parts[0])!!
        val to = if (parts.size == 1) from else TaskStage.byKeyword(parts[1])!!
        return TaskBinding(from, to)
    }

    private fun stopSequences(agent: ParsedArg?): List<String>? {
        val raw = agent?.subValue(STOP_SEQUENCE) ?: return null
        val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.size > MAX_STOP_SEQUENCES) {
            bailTooMany("-stopSequence", parts.size, MAX_STOP_SEQUENCES)
        }
        return parts
    }

    private fun memoryMode(raw: String?): MemoryMode? = when (raw) {
        "system" -> MemoryMode.SYSTEM
        "preamble" -> MemoryMode.PREAMBLE
        else -> null // "none" or absent
    }

    private fun strategyKind(raw: String?): ContextStrategyKind = when (raw) {
        "window" -> ContextStrategyKind.WINDOW
        "facts" -> ContextStrategyKind.FACTS
        "summary" -> ContextStrategyKind.SUMMARY
        else -> ContextStrategyKind.FULL
    }

    private fun scheduleSpecs(controls: List<ParsedArg>): List<ScheduleSpec> =
        controls.filter { it.arg == SCHEDULE && it.value != null }.map(::scheduleSpec)

    /** Build one [ScheduleSpec] from a `-schedule` control; throws on a missing/ambiguous piece. */
    private fun scheduleSpec(c: ParsedArg): ScheduleSpec {
        val after = c.subValue(AFTER)?.toInt()
        val every = c.subValue(EVERY)?.toInt()
        if (after != null && every != null) {
            bailInvalid("-schedule", "after+every", "use exactly one of after / every")
        }
        val seconds = after ?: every
            ?: bailMissing("-schedule", "needs after <sec> or every <sec>")
        val periodic = every != null
        return when (c.value) {
            "collect" -> ScheduleSpec.Collect(
                tool = c.subValue(TOOL)
                    ?: bailMissing("-schedule collect", "needs tool <name>"),
                args = c.subValue(ARGS), seconds = seconds, periodic = periodic,
            )
            "agent" -> ScheduleSpec.Agent(
                prompt = c.subValue(PROMPT)
                    ?: bailMissing("-schedule agent", "needs prompt <text>"),
                seconds = seconds, periodic = periodic,
            )
            else -> bailInvalid("-schedule", c.value ?: "", "collect or agent")
        }
    }

    // ---- admin modes ------------------------------------------------------

    private fun adminCommand(controls: List<ParsedArg>): StartCommand {
        controls.last(INFLATE)?.let { inflate ->
            val session = controls.last(SESSION)?.value
                ?: bailMissing("-session", "required by -inflate")
            return StartCommand.InflateSession(session, inflate.value!!.toInt())
        }
        controls.last(SESSION)?.let { session ->
            if (session.value == null && session.subs.isEmpty()) return StartCommand.ListSessions
            session.sub(CLEAR)?.let { clear ->
                return when {
                    clear.value != null -> StartCommand.CleanSession(clear.value)
                    session.value == null -> StartCommand.CleanHistory
                    else -> gap("session <name> clear")   // wrong order: use `session clear <name>`
                }
            }
            gap("session ${session.subs.firstOrNull()?.arg?.title ?: session.value}")
        }
        controls.last(MEMORY)?.let { return StartCommand.MemoryOp(MemoryAction.Show) }
        controls.last(PROFILE)?.let { return StartCommand.MemoryOp(profileAction(it)) }
        controls.last(RULE)?.let { return StartCommand.MemoryOp(ruleAction(it)) }
        controls.last(TASK)?.let { return StartCommand.MemoryOp(taskAction(it)) }
        bailMissing("-prompt")
    }

    private fun profileAction(p: ParsedArg): MemoryAction {
        val name = p.value
        // A section keyword as a sub (`profile [<name>] <section> [<text>]`); value absent = clear.
        val section = SECTIONS.firstNotNullOfOrNull { arg -> p.sub(arg)?.let { it.value to section(arg) } }
        p.sub(SHOW)?.let {
            return when {
                it.value != null -> MemoryAction.ShowProfile(it.value)
                name == null -> gap("profile show")   // no show-all at startup
                else -> gap("profile <name> show")    // wrong order: use `profile show <name>`
            }
        }
        if (section != null) {
            val (text, sec) = section
            return when {
                name == null && text != null -> MemoryAction.AddProfileItem(sec, text)
                name == null -> MemoryAction.ClearProfileSection(sec)
                text != null -> MemoryAction.AddNamedProfileItem(name, sec, text)
                else -> MemoryAction.ClearNamedProfileSection(name, sec)
            }
        }
        // clear target = the clear-sub's value (verb-then-name); bare = all.
        p.sub(CLEAR)?.let {
            return when {
                it.value != null -> MemoryAction.ClearNamedProfile(it.value)
                name == null -> MemoryAction.ClearAllProfiles
                else -> gap("profile <name> clear")   // wrong order: use `profile clear <name>`
            }
        }
        return name?.let(MemoryAction::TouchProfile) ?: MemoryAction.ListProfiles
    }

    private fun ruleAction(r: ParsedArg): MemoryAction {
        r.sub(CLEAR)?.let {
            return when {
                it.value != null -> MemoryAction.RemoveRule(it.value)
                r.value == null -> MemoryAction.ClearRules
                else -> gap("rule clear")             // wrong order: use `rule clear <id>`
            }
        }
        return r.value?.let(MemoryAction::AddRule) ?: gap("rule")
    }

    private fun taskAction(t: ParsedArg): MemoryAction {
        t.sub(CLEAR)?.let {
            return when {
                it.value != null -> MemoryAction.DeleteTask(it.value)
                t.value == null -> MemoryAction.ClearTasks
                else -> gap("task clear")             // wrong order: use `task clear <id>`
            }
        }
        val id = t.value ?: gap("task")
        return when {
            t.sub(PAUSE) != null -> MemoryAction.PauseTask(id)
            t.sub(RESUME) != null -> MemoryAction.ResumeTask(id)
            t.sub(NOTE) != null -> gap("task note")
            t.subs.isEmpty() -> MemoryAction.SetTask(id)
            else -> gap("task ${t.subs.first().arg.title}")
        }
    }

    private fun section(arg: CliArg): ProfileSection = ProfileSection.byKeyword(arg.title)!!

    private fun gap(what: String): Nothing =
        bailInvalid(what, what, "not expressible as a legacy command")

    private companion object {
        val SECTIONS = listOf(STYLE, FORMAT, CONSTRAINTS, CONTEXT)
        const val DEFAULT_CHUNK_CHARS = 2500
        const val DEFAULT_KEEP_LAST = 6
        const val DEFAULT_SUMMARIZE_EVERY = 10
    }
}

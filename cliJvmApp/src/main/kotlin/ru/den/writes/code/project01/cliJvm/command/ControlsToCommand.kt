package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.cliJvm.ContextStrategyKind
import ru.den.writes.code.project01.cliJvm.StageAgentSpec
import ru.den.writes.code.project01.cliJvm.StageJudgeSpec
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.AGENT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.BY_LINE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CHUNK_CHARS
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CLEAR
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.END_SEQUENCE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.FEED_FILE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.FEED_INSTRUCTION
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.INFLATE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.JUDGE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.KEEP_LAST
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MAX_TOKENS
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MCP_SERVER
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MEMORY
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MODE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MODEL
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.ONESHOT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PROFILE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PROMPT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PROVIDER
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SESSION
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STAGES
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STOP_SEQUENCE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STRATEGY
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SUMMARIZE_EVERY
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.TASK
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.TEMPERATURE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CONSTRAINTS
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CONTEXT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.FORMAT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.NOTE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PAUSE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.RESUME
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.RULE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SHOW
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STYLE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.TUI
import ru.den.writes.code.project01.cliJvm.clicontrols.ParsedControl
import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.ProfileSection
import ru.den.writes.code.project01.shared.memory.TaskBinding
import ru.den.writes.code.project01.shared.memory.TaskStage

/**
 * Maps parsed CliControls top-level [ParsedControl]s onto a domain [CliCommand]
 * — the redesigned grammar's bridge to the same domain the legacy parser feeds.
 * The new grammar bundles provider/model/knobs/stages/judge under `agent`, so a
 * single agent without stages/judge is the "primary" (default agent); agents
 * with `stages` become stage agents, with `judge` become judges. Defaults match
 * the legacy ones. New-grammar productions with no legacy target throw an
 * `InvalidArgumentValue` "not expressible …" (see [gap]).
 */
internal class ControlsToCommand(private val keys: ApiKeys) {

    fun map(controls: List<ParsedControl>): CliCommand {
        val prompt = controls.last(PROMPT)
        return if (prompt != null) promptCommand(controls, prompt) else adminCommand(controls)
    }

    // ---- prompt modes -----------------------------------------------------

    private fun promptCommand(controls: List<ParsedControl>, prompt: ParsedControl): CliCommand {
        val agents = controls.filter { it.arg == AGENT }
        val primaries = agents.filter { it.sub(JUDGE) == null && it.sub(STAGES) == null }
        if (primaries.size > 1) {
            throw CliArgsException.InvalidArgumentValue("-agent", "(multiple)", "exactly one agent without stages/judge")
        }
        val primary = primaries.singleOrNull()

        if (controls.has(ONESHOT)) {
            // The catalog already bars session/feed/strategy/tui/mcp/mode/stages/judge with oneshot.
            return CliCommand.RunOneShot(
                prompt = prompt.value!!,
                maxTokens = primary?.subValue(MAX_TOKENS)?.toInt(),
                stopSequences = stopSequences(primary),
                endSequence = primary?.subValue(END_SEQUENCE),
                temperature = primary?.subValue(TEMPERATURE)?.toDouble(),
                modelProvider = buildProvider(primary),
            )
        }

        val stageAgents = agents.filter { it.sub(JUDGE) == null && it.sub(STAGES) != null }.map { stageSpec(it) }
        val judgeAgents = agents.filter { it.sub(JUDGE) != null }.map { judgeSpec(it) }
        val memoryMode = memoryMode(primary?.subValue(MODE))
        if (stageAgents.isNotEmpty() && memoryMode == null) {
            throw CliArgsException.InvalidArgumentValue("-agent", "stages", "stage agents need a memory mode")
        }
        if (judgeAgents.isNotEmpty() && stageAgents.isEmpty()) {
            throw CliArgsException.InvalidArgumentValue("-agent", "judge", "a judge needs a stage agent")
        }
        val feed = controls.last(FEED_FILE)
        val strategy = controls.last(STRATEGY)
        return CliCommand.RunChat(
            prompt = prompt.value!!,
            maxTokens = primary?.subValue(MAX_TOKENS)?.toInt(),
            stopSequences = stopSequences(primary),
            endSequence = primary?.subValue(END_SEQUENCE),
            temperature = primary?.subValue(TEMPERATURE)?.toDouble(),
            modelProvider = buildProvider(primary),
            session = controls.last(SESSION)?.value,
            feedFile = feed?.value,
            chunkChars = feed?.subValue(CHUNK_CHARS)?.toInt() ?: DEFAULT_CHUNK_CHARS,
            feedInstruction = feed?.subValue(FEED_INSTRUCTION) ?: "",
            byLine = feed?.sub(BY_LINE) != null,
            strategy = strategyKind(strategy?.value),
            keepLast = strategy?.subValue(KEEP_LAST)?.toInt() ?: DEFAULT_KEEP_LAST,
            summarizeEvery = strategy?.subValue(SUMMARIZE_EVERY)?.toInt() ?: DEFAULT_SUMMARIZE_EVERY,
            task = controls.last(TASK)?.value,
            profile = primary?.subValue(PROFILE),
            memoryMode = memoryMode,
            stageAgents = stageAgents,
            tui = controls.has(TUI),
            judgeAgents = judgeAgents,
            mcpServer = controls.last(MCP_SERVER)?.value,
        )
    }

    private fun buildProvider(agent: ParsedControl?): ModelProvider =
        CliArgs.buildModelProvider(
            agent?.subValue(PROVIDER) ?: "gemini",
            agent?.subValue(MODEL),
            keys.gemini, keys.openRouter, keys.huggingFace,
        )

    private fun stageSpec(agent: ParsedControl): StageAgentSpec =
        StageAgentSpec(stageBinding(agent.subValue(STAGES)!!), buildProvider(agent), agent.subValue(PROFILE))

    private fun judgeSpec(agent: ParsedControl): StageJudgeSpec {
        if (agent.sub(STAGES) == null) {
            throw CliArgsException.InvalidArgumentValue("-agent", "judge", "a judge needs a stage span")
        }
        if (agent.sub(PROFILE) != null) {
            throw CliArgsException.InvalidArgumentValue("-agent", "judge", "a judge takes no profile")
        }
        return StageJudgeSpec(stageBinding(agent.subValue(STAGES)!!), buildProvider(agent))
    }

    /** Both ends are already validated (known stages, from ≤ to) by the catalog's StageRange. */
    private fun stageBinding(raw: String): TaskBinding {
        val parts = raw.split("..")
        val from = TaskStage.byKeyword(parts[0])!!
        val to = if (parts.size == 1) from else TaskStage.byKeyword(parts[1])!!
        return TaskBinding(from, to)
    }

    private fun stopSequences(agent: ParsedControl?): List<String>? {
        val raw = agent?.subValue(STOP_SEQUENCE) ?: return null
        val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.size > CliArgs.MAX_STOP_SEQUENCES) {
            throw CliArgsException.TooManyValues("-stopSequence", parts.size, CliArgs.MAX_STOP_SEQUENCES)
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

    // ---- admin modes ------------------------------------------------------

    private fun adminCommand(controls: List<ParsedControl>): CliCommand {
        controls.last(INFLATE)?.let { inflate ->
            val session = controls.last(SESSION)?.value
                ?: throw CliArgsException.MissingRequiredArgument("-session", "required by -inflate")
            return CliCommand.InflateSession(session, inflate.value!!.toInt())
        }
        controls.last(SESSION)?.let { session ->
            if (session.value == null && session.subs.isEmpty()) return CliCommand.ListSessions
            session.sub(CLEAR)?.let { clear ->
                return when {
                    clear.value != null -> CliCommand.CleanSession(clear.value)
                    session.value == null -> CliCommand.CleanHistory
                    else -> gap("session <name> clear")   // wrong order: use `session clear <name>`
                }
            }
            gap("session ${session.subs.firstOrNull()?.arg?.title ?: session.value}")
        }
        controls.last(MEMORY)?.let { return CliCommand.MemoryOp(MemoryAction.Show) }
        controls.last(PROFILE)?.let { return CliCommand.MemoryOp(profileAction(it)) }
        controls.last(RULE)?.let { return CliCommand.MemoryOp(ruleAction(it)) }
        controls.last(TASK)?.let { return CliCommand.MemoryOp(taskAction(it)) }
        throw CliArgsException.MissingRequiredArgument("-prompt")
    }

    private fun profileAction(p: ParsedControl): MemoryAction {
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

    private fun ruleAction(r: ParsedControl): MemoryAction {
        r.sub(CLEAR)?.let {
            return when {
                it.value != null -> MemoryAction.RemoveRule(it.value)
                r.value == null -> MemoryAction.ClearRules
                else -> gap("rule clear")             // wrong order: use `rule clear <id>`
            }
        }
        return r.value?.let(MemoryAction::AddRule) ?: gap("rule")
    }

    private fun taskAction(t: ParsedControl): MemoryAction {
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

    private fun section(arg: CliControlsArg): ProfileSection = ProfileSection.byKeyword(arg.title)!!

    private fun gap(what: String): Nothing =
        throw CliArgsException.InvalidArgumentValue(what, what, "not expressible as a legacy command")

    private companion object {
        val SECTIONS = listOf(STYLE, FORMAT, CONSTRAINTS, CONTEXT)
        const val DEFAULT_CHUNK_CHARS = 2500
        const val DEFAULT_KEEP_LAST = 6
        const val DEFAULT_SUMMARIZE_EVERY = 10
    }
}

private fun ParsedControl.subValue(arg: CliControlsArg): String? = sub(arg)?.value
private fun List<ParsedControl>.last(arg: CliControlsArg): ParsedControl? = lastOrNull { it.arg == arg }
private fun List<ParsedControl>.has(arg: CliControlsArg): Boolean = any { it.arg == arg }

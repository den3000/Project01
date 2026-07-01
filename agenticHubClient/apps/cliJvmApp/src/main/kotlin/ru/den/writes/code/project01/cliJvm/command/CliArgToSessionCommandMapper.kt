package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.SessionCommand
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.AFTER
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.AGENT
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.ARGS
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.BRANCH
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.CLEAR
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.CONSTRAINTS
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.CONTEXT
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.EVERY
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.FORMAT
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.MEMORY
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.MODE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.NOTE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.PAUSE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.PROFILE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.PROMPT
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.RESUME
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.RULE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.SCHEDULE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.SHOW
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.STYLE
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.SWITCH
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.TASK
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.TOOL
import ru.den.writes.code.project01.cliJvm.cliargs.CliArgsParser
import ru.den.writes.code.project01.cliJvm.cliargs.ParseResult
import ru.den.writes.code.project01.cliJvm.cliargs.ParsedArg
import ru.den.writes.code.project01.cliJvm.cliargs.Surface
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.ProfileSection

/**
 * Maps a typed REPL line onto an in-session [SessionCommand] by parsing it against
 * the shared catalog on the [Surface.CMD] front — the `/`-command twin of
 * [CliArgsToStartCommandMapper] (which feeds the startup `StartCommand`). One entity grammar
 * serves both fronts; only the target domain differs. A line that isn't a known
 * control (any parse error) returns null, so the caller sends it to the model as
 * an ordinary prompt.
 *
 * Verb-then-name is strict: the name for `show` / `clear` is the verb-sub's value
 * (`profile show coder`, `profile clear coder`). The reverse order
 * (`profile coder show`) is not a command — it returns null (→ prompt) rather than
 * silently listing or, for clear, nuking everything.
 *
 * Front-specific choices: `profile <name>` *activates* the profile here
 * (in-session select = use), whereas startup touch-creates it; `task`'s
 * pause/resume/note act on the **active** task; and the memory-injection mode is
 * flipped with `agent mode <preamble|system>` (the agent always exists).
 */
internal class CliArgToSessionCommandMapper(private val parser: CliArgsParser) {

    /** The branch/memory command for [line], or null if it isn't one (→ a normal prompt). */
    fun parse(line: String): SessionCommand? = when (val r = parser.parse(line, Surface.CMD)) {
        is ParseResult.Ok -> map(r.control)
        is ParseResult.Err -> null
    }

    private fun map(c: ParsedArg): SessionCommand? = when (c.arg) {
        BRANCH -> branch(c)
        MEMORY -> SessionCommand.ShowMemory
        AGENT -> agentMode(c)
        PROFILE -> profile(c)
        RULE -> rule(c)
        TASK -> task(c)
        SCHEDULE -> schedule(c)
        else -> null // session/strategy/inflate/mcp/reuse/exit/help — not in-session commands
    }

    private fun branch(c: ParsedArg): SessionCommand? {
        c.sub(SHOW)?.let { return if (c.value == null) SessionCommand.Checkpoint else null } // `branch show` = current branch + count
        c.sub(SWITCH)?.let { return SessionCommand.Switch(it.value.orEmpty()) }
        c.sub(CLEAR)?.let {
            return when {
                it.value != null -> SessionCommand.DeleteBranch(it.value)  // `branch clear <name>`
                c.value == null -> SessionCommand.ClearBranches            // bare `branch clear` = all but current
                else -> null                                              // `branch <name> clear` — wrong order
            }
        }
        return c.value?.let(SessionCommand::Branch) ?: SessionCommand.ListBranches
    }

    private fun profile(c: ParsedArg): SessionCommand? {
        val name = c.value
        // A section keyword as a sub (`profile [<name>] <section> [<text>]`); value absent = clear.
        val section = SECTIONS.firstNotNullOfOrNull { arg -> c.sub(arg)?.let { it.value to section(arg) } }
        c.sub(SHOW)?.let {
            return when {
                it.value != null -> SessionCommand.ShowProfile(it.value)
                name == null -> SessionCommand.ListProfiles      // bare `profile show` = list
                else -> null                                    // `profile <name> show` — wrong order
            }
        }
        if (section != null) {
            val (text, sec) = section
            return when {
                name == null && text != null -> SessionCommand.AddProfileItem(sec, text)
                name == null -> SessionCommand.ClearProfileSection(sec)
                text != null -> SessionCommand.AddNamedProfileItem(name, sec, text)
                else -> SessionCommand.ClearNamedProfileSection(name, sec)
            }
        }
        c.sub(CLEAR)?.let {
            return when {
                it.value != null -> SessionCommand.ClearNamedProfile(it.value)
                name == null -> SessionCommand.ClearAllProfiles  // bare `profile clear` = all
                else -> null                                    // `profile <name> clear` — wrong order
            }
        }
        // In-session select = activate (touch-creates if missing); bare = list.
        return name?.let(SessionCommand::SwitchProfile) ?: SessionCommand.ListProfiles
    }

    private fun rule(c: ParsedArg): SessionCommand? {
        c.sub(CLEAR)?.let {
            return when {
                it.value != null -> SessionCommand.RemoveRule(it.value)
                c.value == null -> SessionCommand.ClearRules     // bare `rule clear` = all
                else -> null                                    // `rule <text> clear` — wrong order
            }
        }
        return SessionCommand.AddRule(c.value.orEmpty())
    }

    private fun task(c: ParsedArg): SessionCommand? {
        c.sub(CLEAR)?.let {
            return when {
                it.value != null -> SessionCommand.DeleteTask(it.value)
                c.value == null -> SessionCommand.ClearTasks     // bare `task clear` = all
                else -> null                                    // `task <id> clear` — wrong order
            }
        }
        // pause/resume/note act on the active task (no id).
        c.sub(PAUSE)?.let { return SessionCommand.PauseTask }
        c.sub(RESUME)?.let { return SessionCommand.ResumeTask }
        c.sub(NOTE)?.let { return SessionCommand.AppendTaskNote(it.value.orEmpty()) }
        return SessionCommand.SetTask(c.value.orEmpty())
    }

    /** `/schedule collect tool <name> [args …] | agent prompt "<text>"` + after/every <sec>. */
    private fun schedule(c: ParsedArg): SessionCommand? {
        // clear [<id>] = cancel one / all active; bare /schedule (no kind) = list.
        c.sub(CLEAR)?.let {
            return if (it.value != null) SessionCommand.CancelSchedule(it.value) else SessionCommand.ClearSchedules
        }
        if (c.value == null) return SessionCommand.ListSchedules
        val after = c.sub(AFTER)?.value?.toIntOrNull()
        val every = c.sub(EVERY)?.value?.toIntOrNull()
        if (after != null && every != null) return null
        val seconds = after ?: every ?: return null
        val periodic = every != null
        val spec = when (c.value) {
            "collect" -> ScheduleSpec.Collect(c.sub(TOOL)?.value ?: return null, c.sub(ARGS)?.value, seconds, periodic)
            "agent" -> ScheduleSpec.Agent(c.sub(PROMPT)?.value ?: return null, seconds, periodic)
            else -> return null
        }
        return SessionCommand.Schedule(spec)
    }

    /** `agent mode <preamble|system>` flips the live memory-injection mode. */
    private fun agentMode(c: ParsedArg): SessionCommand? = when (c.sub(MODE)?.value) {
        "preamble" -> SessionCommand.SetMemoryMode(MemoryMode.PREAMBLE)
        "system" -> SessionCommand.SetMemoryMode(MemoryMode.SYSTEM)
        else -> null // `none` can't disable a live provider; other agent subs aren't in-session ops
    }

    private fun section(arg: CliArg): ProfileSection = ProfileSection.byKeyword(arg.title)!!

    private companion object {
        val SECTIONS = listOf(STYLE, FORMAT, CONSTRAINTS, CONTEXT)
    }
}

package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.BranchCommand
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.AGENT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.BRANCH
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CLEAR
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CONSTRAINTS
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CONTEXT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.FORMAT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MEMORY
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MODE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.NOTE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PAUSE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PROFILE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.RESUME
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.RULE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SHOW
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STYLE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SWITCH
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.TASK
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsParser
import ru.den.writes.code.project01.cliJvm.clicontrols.ParseResult
import ru.den.writes.code.project01.cliJvm.clicontrols.ParsedControl
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.ProfileSection

/**
 * Maps a typed REPL line onto an in-session [BranchCommand] by parsing it against
 * the shared catalog on the [Surface.CMD] front — the `/`-command twin of
 * [ControlsToCommand] (which feeds the startup `CliCommand`). One entity grammar
 * serves both fronts; only the target domain differs. A line that isn't a known
 * control (any parse error) returns null, so the caller sends it to the model as
 * an ordinary prompt — matching the old hand-rolled `parseSlashCommand`.
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
internal class ControlsToBranchCommand(private val parser: CliControlsParser = CliControlsParser()) {

    /** The branch/memory command for [line], or null if it isn't one (→ a normal prompt). */
    fun parse(line: String): BranchCommand? = when (val r = parser.parse(line, Surface.CMD)) {
        is ParseResult.Ok -> map(r.control)
        is ParseResult.Err -> null
    }

    private fun map(c: ParsedControl): BranchCommand? = when (c.arg) {
        BRANCH -> branch(c)
        MEMORY -> BranchCommand.ShowMemory
        AGENT -> agentMode(c)
        PROFILE -> profile(c)
        RULE -> rule(c)
        TASK -> task(c)
        else -> null // session/strategy/inflate/mcp/reuse/exit/help — not in-session commands
    }

    private fun branch(c: ParsedControl): BranchCommand? {
        c.sub(SHOW)?.let { return if (c.value == null) BranchCommand.Checkpoint else null } // `branch show` = current branch + count
        c.sub(SWITCH)?.let { return BranchCommand.Switch(it.value.orEmpty()) }
        c.sub(CLEAR)?.let {
            return when {
                it.value != null -> BranchCommand.DeleteBranch(it.value)  // `branch clear <name>`
                c.value == null -> BranchCommand.ClearBranches            // bare `branch clear` = all but current
                else -> null                                              // `branch <name> clear` — wrong order
            }
        }
        return c.value?.let(BranchCommand::Branch) ?: BranchCommand.ListBranches
    }

    private fun profile(c: ParsedControl): BranchCommand? {
        val name = c.value
        // A section keyword as a sub (`profile [<name>] <section> [<text>]`); value absent = clear.
        val section = SECTIONS.firstNotNullOfOrNull { arg -> c.sub(arg)?.let { it.value to section(arg) } }
        c.sub(SHOW)?.let {
            return when {
                it.value != null -> BranchCommand.ShowProfile(it.value)
                name == null -> BranchCommand.ListProfiles      // bare `profile show` = list
                else -> null                                    // `profile <name> show` — wrong order
            }
        }
        if (section != null) {
            val (text, sec) = section
            return when {
                name == null && text != null -> BranchCommand.AddProfileItem(sec, text)
                name == null -> BranchCommand.ClearProfileSection(sec)
                text != null -> BranchCommand.AddNamedProfileItem(name, sec, text)
                else -> BranchCommand.ClearNamedProfileSection(name, sec)
            }
        }
        c.sub(CLEAR)?.let {
            return when {
                it.value != null -> BranchCommand.ClearNamedProfile(it.value)
                name == null -> BranchCommand.ClearAllProfiles  // bare `profile clear` = all
                else -> null                                    // `profile <name> clear` — wrong order
            }
        }
        // In-session select = activate (touch-creates if missing); bare = list.
        return name?.let(BranchCommand::SwitchProfile) ?: BranchCommand.ListProfiles
    }

    private fun rule(c: ParsedControl): BranchCommand? {
        c.sub(CLEAR)?.let {
            return when {
                it.value != null -> BranchCommand.RemoveRule(it.value)
                c.value == null -> BranchCommand.ClearRules     // bare `rule clear` = all
                else -> null                                    // `rule <text> clear` — wrong order
            }
        }
        return BranchCommand.AddRule(c.value.orEmpty())
    }

    private fun task(c: ParsedControl): BranchCommand? {
        c.sub(CLEAR)?.let {
            return when {
                it.value != null -> BranchCommand.DeleteTask(it.value)
                c.value == null -> BranchCommand.ClearTasks     // bare `task clear` = all
                else -> null                                    // `task <id> clear` — wrong order
            }
        }
        // pause/resume/note act on the active task (no id).
        c.sub(PAUSE)?.let { return BranchCommand.PauseTask }
        c.sub(RESUME)?.let { return BranchCommand.ResumeTask }
        c.sub(NOTE)?.let { return BranchCommand.AppendTaskNote(it.value.orEmpty()) }
        return BranchCommand.SetTask(c.value.orEmpty())
    }

    /** `agent mode <preamble|system>` flips the live memory-injection mode. */
    private fun agentMode(c: ParsedControl): BranchCommand? = when (c.sub(MODE)?.value) {
        "preamble" -> BranchCommand.SetMemoryMode(MemoryMode.PREAMBLE)
        "system" -> BranchCommand.SetMemoryMode(MemoryMode.SYSTEM)
        else -> null // `none` can't disable a live provider; other agent subs aren't in-session ops
    }

    private fun section(arg: CliControlsArg): ProfileSection = ProfileSection.byKeyword(arg.title)!!

    private companion object {
        val SECTIONS = listOf(STYLE, FORMAT, CONSTRAINTS, CONTEXT)
    }
}

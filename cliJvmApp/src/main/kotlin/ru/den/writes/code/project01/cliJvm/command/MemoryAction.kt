package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.shared.memory.ProfileSection

/**
 * What a memory invocation does — the domain command behind `-memory …` (legacy)
 * and the `profile`/`rule`/`task` entity ops (CliControls). Carried by
 * [CliCommand.MemoryOp]; executed against the on-disk memory store.
 */
internal sealed interface MemoryAction {
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
    /** Delete every profile — all named ones and the unnamed default. */
    data object ClearAllProfiles : MemoryAction

    /** Append a new rule under `rules/`. */
    data class AddRule(val text: String) : MemoryAction
    /** Delete the rule with this id (three-digit prefix). */
    data class RemoveRule(val id: String) : MemoryAction
    /** Delete every rule. */
    data object ClearRules : MemoryAction
    /** Create/select a task file under `tasks/<taskId>.md`. */
    data class SetTask(val taskId: String) : MemoryAction
    /** Pause the task — hold its FSM stage. */
    data class PauseTask(val taskId: String) : MemoryAction
    /** Resume the task — clear the pause flag. */
    data class ResumeTask(val taskId: String) : MemoryAction
    /** Delete one task by id. */
    data class DeleteTask(val taskId: String) : MemoryAction
    /** Delete every task. */
    data object ClearTasks : MemoryAction
}

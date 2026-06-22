package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.shared.memory.ProfileSection
import ru.den.writes.code.project01.shared.memory.TaskNotes
import ru.den.writes.code.project01.shared.memory.TaskStage
import ru.den.writes.code.project01.shared.memory.isValidProfileName

/** Valid branch names: alphanumerics, '_' or '-' — same shape as session names. */
private val BRANCH_NAME_REGEX = Regex("^[a-zA-Z0-9_-]+$")

/**
 * Executes a REPL branch- / memory-management command and RETURNS the status
 * line(s) instead of printing them. The DB work (and disk work for memory) is
 * suspend / blocking and lives here; the caller decides where the lines go —
 * `SessionLoop` writes them to stderr, the view-model wraps each as a
 * `UiLine.Notice`. Each returned string carries its own `[tag]` prefix and is
 * printed verbatim (one `println` each), so a multi-line entry stays a single
 * element with embedded newlines.
 *
 * Branch commands need a persisted session; memory commands need a configured
 * [memory] provider — each yields an explanatory line when its dependency is
 * absent, mirroring the previous inline behaviour.
 */
internal class CommandRunner(
    private val historyStore: HistoryStore?,
    private val memory: MemoryProvider?,
    private val strategy: ContextStrategy,
) {
    suspend fun run(command: BranchCommand): List<String> = buildList {
        when (command) {
            BranchCommand.Checkpoint -> withHistoryStore { store ->
                add(
                    "[checkpoint] branch '${store.branchId}', ${store.messages.size} message(s) — " +
                        "use /branch <name> to fork a new branch from here"
                )
            }
            BranchCommand.ListBranches -> withHistoryStore { store ->
                val branches = store.branches()
                add("[branches] " + branches.joinToString(", ") { if (it == store.branchId) "* $it" else it })
            }
            is BranchCommand.Branch -> withHistoryStore { store ->
                val name = command.name
                when {
                    !name.matches(BRANCH_NAME_REGEX) ->
                        add("[branch] invalid name '$name' (letters, digits, '_' or '-')")
                    name == store.branchId ->
                        add("[branch] already on '$name'")
                    name in store.branches() ->
                        add("[branch] '$name' already exists — use /switch $name")
                    else -> {
                        val copied = store.messages.size
                        store.fork(name)
                        add(
                            "[branch] forked '${store.branchId}' → '$name' ($copied message(s) copied); " +
                                "/switch $name to continue on it"
                        )
                    }
                }
            }
            is BranchCommand.Switch -> withHistoryStore { store ->
                val name = command.name
                when {
                    name == store.branchId -> add("[branch] already on '$name'")
                    name !in store.branches() ->
                        add("[branch] no such branch '$name' (use /branches to list)")
                    else -> {
                        store.switchTo(name)
                        strategy.rebind(store)
                        add("[branch] switched to '$name' (${store.messages.size / 2} prior turn(s))")
                    }
                }
            }
            BranchCommand.ShowMemory -> withMemory { mem ->
                add("[memory]\n${mem.describe()}")
            }
            is BranchCommand.SetProfile -> withMemory { mem ->
                if (command.text.isBlank()) {
                    add("[memory] /profile needs the new profile text")
                } else {
                    mem.store.saveProfile(command.text)
                    add("[memory] profile saved (${command.text.length} char(s))")
                }
            }
            is BranchCommand.AddProfileItem -> withMemory { mem ->
                if (command.text.isBlank()) {
                    add("[memory] /profile ${command.section.keyword} needs the new text")
                } else {
                    val updated = mem.store.addProfileItem(command.section, command.text)
                    val count = updated.items(command.section).size
                    add("[memory] profile.${command.section.keyword} += \"${command.text}\" ($count item(s) total)")
                }
            }
            is BranchCommand.ClearProfileSection -> withMemory { mem ->
                mem.store.clearProfileSection(command.section)
                add("[memory] profile.${command.section.keyword} cleared")
            }
            BranchCommand.ClearProfile -> withMemory { mem ->
                mem.store.clearProfile()
                add("[memory] profile cleared")
            }
            is BranchCommand.SwitchProfile -> withMemory { mem ->
                val name = command.name
                if (!isValidProfileName(name)) {
                    add("[memory] invalid profile name '$name' (alphanumeric / '_' / '-', up to 64 chars)")
                } else {
                    mem.setActiveProfile(name)
                    add("[memory] active profile → $name")
                }
            }
            BranchCommand.ListProfiles -> withMemory { mem ->
                val names = mem.store.listProfileNames()
                val active = mem.activeProfileName()
                if (names.isEmpty()) {
                    add("[memory] no named profiles")
                } else {
                    add("[memory] profiles:")
                    names.forEach { name ->
                        val marker = if (name == active) "* " else "  "
                        add("  $marker$name")
                    }
                }
            }
            is BranchCommand.ShowProfile -> withMemory { mem ->
                val name = command.name
                val data = mem.store.loadNamedProfile(name)
                if (data == null) {
                    add("[memory] profile '$name' is empty or absent")
                } else {
                    add("[profile:$name]")
                    data.freeText?.takeIf { it.isNotBlank() }?.let { add(it.trim()) }
                    for (section in ProfileSection.entries) {
                        val items = data.items(section)
                        if (items.isEmpty()) continue
                        add("${section.keyword}: ${items.joinToString(", ")}")
                    }
                }
            }
            is BranchCommand.TouchProfile -> withMemory { mem ->
                val name = command.name
                if (!isValidProfileName(name)) {
                    add("[memory] invalid profile name '$name'")
                } else {
                    mem.store.touchNamedProfile(name)
                    add("[memory] profile '$name' ready (use /profile-use $name to activate)")
                }
            }
            is BranchCommand.AddNamedProfileItem -> withMemory { mem ->
                val name = command.name
                if (!isValidProfileName(name)) {
                    add("[memory] invalid profile name '$name'")
                } else if (command.text.isBlank()) {
                    add("[memory] /profile $name ${command.section.keyword} needs the new text")
                } else {
                    val updated = mem.store.addNamedProfileItem(name, command.section, command.text)
                    val count = updated.items(command.section).size
                    add("[memory] profile.$name.${command.section.keyword} += \"${command.text}\" ($count item(s) total)")
                }
            }
            is BranchCommand.ClearNamedProfileSection -> withMemory { mem ->
                mem.store.clearNamedProfileSection(command.name, command.section)
                add("[memory] profile.${command.name}.${command.section.keyword} cleared")
            }
            is BranchCommand.ClearNamedProfile -> withMemory { mem ->
                val removed = mem.store.clearNamedProfile(command.name)
                if (removed) add("[memory] profile '${command.name}' removed")
                else add("[memory] no profile named '${command.name}'")
            }
            is BranchCommand.AddRule -> withMemory { mem ->
                if (command.text.isBlank()) {
                    add("[memory] /rule needs the new rule text")
                } else {
                    val rule = mem.store.addRule(command.text)
                    add("[memory] rule ${rule.id} added")
                }
            }
            is BranchCommand.SetTask -> withMemory { mem ->
                val id = command.taskId
                if (id.isBlank()) {
                    add("[memory] /task needs a task id")
                } else {
                    mem.setTask(id)
                    // Touch-create so the file is visible on `/memory` and on disk
                    // even before the first note is appended. A new task starts at
                    // the initial FSM stage so it has formalized state from turn one.
                    val created = mem.store.loadTask(id) == null
                    if (created) mem.store.saveTask(TaskNotes(taskId = id, stage = TaskStage.INITIAL))
                    add(
                        "[memory] active task → $id" +
                            if (created) " (new, stage ${TaskStage.INITIAL.keyword})" else ""
                    )
                }
            }
            is BranchCommand.AppendTaskNote -> withMemory { mem ->
                val active = mem.activeTaskId()
                when {
                    active == null ->
                        add("[memory] /task-note needs an active task — set one with /task <id>")
                    command.note.isBlank() ->
                        add("[memory] /task-note needs the note text")
                    else -> {
                        mem.store.appendTaskNote(active, command.note)
                        add("[memory] note appended to task '$active'")
                    }
                }
            }
            BranchCommand.PauseTask -> withMemory { mem -> togglePause(mem, paused = true) }
            BranchCommand.ResumeTask -> withMemory { mem -> togglePause(mem, paused = false) }
            is BranchCommand.SetMemoryMode -> withMemory { mem ->
                mem.setMode(command.mode)
                add("[memory] mode → ${command.mode.name.lowercase()}")
            }
        }
    }

    private inline fun MutableList<String>.withHistoryStore(block: (HistoryStore) -> Unit) {
        val store = historyStore
        if (store == null) add("[branch] branch commands need a persisted session")
        else block(store)
    }

    private inline fun MutableList<String>.withMemory(block: (MemoryProvider) -> Unit) {
        val mem = memory
        if (mem == null) add("[memory] memory commands need -memory-mode <preamble|system> at startup")
        else block(mem)
    }

    /**
     * Flip the active task's `paused` flag. Paused tasks hold their stage, so a
     * task can be parked anywhere and picked up later. No active task → an
     * explanatory line, no write. Mirrors the previous `togglePause`.
     */
    private fun MutableList<String>.togglePause(mem: MemoryProvider, paused: Boolean) {
        val id = mem.activeTaskId()
        if (id == null) {
            add("[task] no active task — set one with /task <id>")
            return
        }
        val task = mem.store.loadTask(id) ?: TaskNotes(id, stage = TaskStage.INITIAL)
        mem.store.saveTask(task.copy(paused = paused))
        val word = if (paused) "paused" else "resumed"
        add("[task] $word — task '$id' at stage ${task.stage?.keyword ?: "(none)"}")
    }
}

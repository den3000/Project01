package ru.den.writes.code.project01.cliJvm.memory

import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.memory.MemoryLayer
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.ProfileData
import ru.den.writes.code.project01.shared.memory.ProfileSection

/**
 * Façade the agent uses for memory: holds the mutable mode + active
 * task id, defers the actual on-disk reads to [store]. Constructed once
 * per chat session in `main.kt`; mode and task id can be flipped from
 * REPL while the session is live.
 *
 * No I/O happens in the constructor — disk reads are deferred to
 * [memoryLayer], which is called once per turn from `Agent.send()`.
 */
internal class MemoryProvider(
    val store: MemoryStore,
    initialMode: MemoryMode = MemoryMode.PREAMBLE,
    initialTaskId: String? = null,
    initialProfileName: String? = null,
) {
    private var mode: MemoryMode = initialMode
    private var taskId: String? = initialTaskId
    private var profileName: String? = initialProfileName

    init {
        // Touch-create the starting named profile (if any) so it shows
        // up in `profile-list` even before the first bullet is added.
        initialProfileName?.let(store::touchNamedProfile)
    }

    fun currentMode(): MemoryMode = mode
    fun setMode(newMode: MemoryMode) { mode = newMode }

    fun activeTaskId(): String? = taskId
    fun setTask(newTaskId: String?) { taskId = newTaskId }

    fun activeProfileName(): String? = profileName

    /**
     * Switch the active named profile. Null switches back to the
     * unnamed `profile.md` fallback. When a non-null [newName] is set,
     * the file is touch-created so it shows up in `listProfileNames`
     * and on disk before any bullets are added.
     */
    fun setActiveProfile(newName: String?) {
        profileName = newName
        if (newName != null) store.touchNamedProfile(newName)
    }

    /**
     * Compose the per-turn memory slice. Returns an empty list when
     * every layer is empty so the wire shape stays byte-identical to a
     * no-memory session. Active named profile (if set) wins over the
     * unnamed `profile.md`.
     */
    fun memoryLayer(): List<Message> {
        val profile = activeProfileData()
        val rules = store.listRules()
        val task = taskId?.let(store::loadTask)
        return when (mode) {
            MemoryMode.PREAMBLE -> MemoryLayer.composePreamble(profile, rules, task)
            MemoryMode.SYSTEM -> MemoryLayer.composeSystem(profile, rules, task)
        }
    }

    /**
     * Profile data the agent will inject on the next turn — the active
     * named profile if one is selected, otherwise the unnamed
     * `profile.md` fallback. Returns null when neither has any content.
     */
    private fun activeProfileData(): ProfileData? = profileName
        ?.let(store::loadNamedProfile)
        ?: store.loadProfileData()

    /**
     * Render the current memory state as a multi-line block for `/memory`
     * (REPL) and `-memory show` (CLI). Includes the active mode + task
     * id + active named profile so the user can see what would be
     * appended on the next turn.
     */
    fun describe(): String = buildString {
        appendLine("mode=${mode.name.lowercase()}")
        appendLine("active task=${taskId ?: "(none)"}")
        appendLine("active profile=${profileName ?: "(default)"}")
        val profile = activeProfileData()
        appendLine()
        appendLine("[profile]")
        if (profile == null) {
            appendLine("(empty)")
        } else {
            appendProfile(profile)
        }
        val names = store.listProfileNames()
        if (names.isNotEmpty()) {
            appendLine()
            appendLine("[profiles]")
            for (name in names) {
                val marker = if (name == profileName) "* " else "- "
                appendLine("$marker$name")
            }
        }
        val rules = store.listRules()
        appendLine()
        appendLine("[rules]")
        if (rules.isEmpty()) appendLine("(empty)")
        else rules.forEach { appendLine("  ${it.id}: ${it.text.lines().first()}") }
        val task = taskId?.let(store::loadTask)
        appendLine()
        appendLine("[task]")
        if (task == null) appendLine("(none)")
        else {
            appendLine("  id     = ${task.taskId}")
            appendLine("  goal   = ${task.goal ?: "(none)"}")
            appendLine("  stage  = ${task.stage?.keyword ?: "(none)"}")
            appendLine("  paused = ${task.paused}")
            if (task.notes.isEmpty()) appendLine("  notes = (none)")
            else {
                appendLine("  notes =")
                task.notes.forEach { appendLine("    - $it") }
            }
        }
    }.trimEnd()

    /**
     * Append a compact [ProfileData] dump: legacy `freeText` (if any) on
     * its own line, then `style: a, b` / `format: …` / `constraints: …` /
     * `context: …` — one line per non-empty section.
     */
    private fun StringBuilder.appendProfile(data: ProfileData) {
        data.freeText?.takeIf { it.isNotBlank() }?.let { appendLine(it.trim()) }
        for (section in ProfileSection.entries) {
            val items = data.items(section)
            if (items.isEmpty()) continue
            append(section.keyword)
            append(": ")
            appendLine(items.joinToString(", "))
        }
    }
}

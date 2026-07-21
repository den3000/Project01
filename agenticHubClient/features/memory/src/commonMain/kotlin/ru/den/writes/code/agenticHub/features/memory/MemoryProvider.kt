package ru.den.writes.code.agenticHub.features.memory

import ru.den.writes.code.agenticHub.features.llm.Message

/**
 * Façade the agent uses for memory: holds the mutable mode + active
 * task id, defers the actual on-disk reads to [store]. Constructed once
 * per chat session in `main.kt`; mode and task id can be flipped from
 * REPL while the session is live.
 *
 * No I/O happens in the constructor — disk reads are deferred to
 * [memoryLayer], which is called once per turn from `Agent.send()`.
 */
public class MemoryProvider(
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
     * Compose the per-turn memory slice for the session's active profile —
     * identical to [memoryLayerFor] with a null argument (the single-agent
     * path).
     */
    fun memoryLayer(): List<Message> = memoryLayerFor(null)

    /**
     * Compose the per-turn memory slice for an agent pinned to profile
     * [agentProfile]. `null` falls back to the session's live active profile
     * (the [memoryLayer] / single-agent path, so REPL `/profile-use` keeps
     * working). Rules and the current task are always the shared live ones —
     * only the profile selection is overridden, so per-stage agents differ by
     * profile, not by rules or task. Returns an empty list when every layer is
     * empty (byte-identical to a no-memory session).
     */
    fun memoryLayerFor(agentProfile: String?): List<Message> {
        val profile = profileDataForAgent(agentProfile)
        val rules = store.listRules()
        val task = taskId?.let(store::loadTask)
        return when (mode) {
            MemoryMode.PREAMBLE -> MemoryLayer.composePreamble(profile, rules, task)
            MemoryMode.SYSTEM -> MemoryLayer.composeSystem(profile, rules, task)
        }
    }

    /**
     * The profile an agent pinned to [agentProfile] speaks with: the pinned one
     * when it exists, else the session's live active profile, else the unnamed
     * `profile.md`. Null when none of them holds anything.
     *
     * The single place that resolution lives. It used to be copied into every
     * consumer, and consumers now want different slices of the same profile —
     * the memory layer wants all of it, the invariant judge only some sections —
     * so a copy per slice would read one profile off disk several times per turn
     * and drift the moment the fallback chain changes.
     */
    fun profileDataForAgent(agentProfile: String?): ProfileData? =
        if (agentProfile != null) {
            store.loadNamedProfile(agentProfile) ?: store.loadProfileData()
        } else {
            activeProfileData()
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

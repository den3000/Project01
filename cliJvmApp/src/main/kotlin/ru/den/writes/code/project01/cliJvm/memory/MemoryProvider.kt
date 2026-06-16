package ru.den.writes.code.project01.cliJvm.memory

import ru.den.writes.code.project01.cliJvm.Message

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
) {
    private var mode: MemoryMode = initialMode
    private var taskId: String? = initialTaskId

    fun currentMode(): MemoryMode = mode
    fun setMode(newMode: MemoryMode) { mode = newMode }

    fun activeTaskId(): String? = taskId
    fun setTask(newTaskId: String?) { taskId = newTaskId }

    /**
     * Compose the per-turn memory slice. Returns an empty list when
     * every layer is empty so the wire shape stays byte-identical to a
     * no-memory session.
     */
    fun memoryLayer(): List<Message> {
        val profile = store.loadProfile()
        val rules = store.listRules()
        val task = taskId?.let(store::loadTask)
        return when (mode) {
            MemoryMode.PREAMBLE -> MemoryLayer.composePreamble(profile, rules, task)
            MemoryMode.SYSTEM -> MemoryLayer.composeSystem(profile, rules, task)
        }
    }

    /**
     * Render the current memory state as a multi-line block for `/memory`
     * (REPL) and `-memory show` (CLI). Includes the active mode + task
     * id so the user can see what would be appended on the next turn.
     */
    fun describe(): String = buildString {
        appendLine("mode=${mode.name.lowercase()}")
        appendLine("active task=${taskId ?: "(none)"}")
        val profile = store.loadProfile()
        appendLine()
        appendLine("[profile]")
        appendLine(profile ?: "(empty)")
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
            appendLine("  id    = ${task.taskId}")
            appendLine("  goal  = ${task.goal ?: "(none)"}")
            appendLine("  stage = ${task.stage ?: "(none)"}")
            if (task.notes.isEmpty()) appendLine("  notes = (none)")
            else {
                appendLine("  notes =")
                task.notes.forEach { appendLine("    - $it") }
            }
        }
    }.trimEnd()
}

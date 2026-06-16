package ru.den.writes.code.project01.cliJvm.memory

import ru.den.writes.code.project01.cliJvm.Message
import ru.den.writes.code.project01.cliJvm.Role

/**
 * Pure functions that turn `(profile, rules, task)` into the message slice
 * the agent prepends to the wire list. Lives outside [MemoryStore] so the
 * composition has no I/O dependency — same shape as `FactsExtractor` next
 * to `StickyFacts`.
 *
 * Both flavours return an empty list when every input is null/blank/empty
 * so a "memory enabled but nothing saved yet" run leaves the wire bytes
 * identical to a no-memory run.
 */
internal object MemoryLayer {
    const val PROFILE_HEADING: String = "[Profile]"
    const val RULES_HEADING: String = "[Rules]"
    const val TASK_HEADING: String = "[Current Task]"
    const val PREAMBLE_ACK: String =
        "Understood. I'll keep that profile, those rules, and the current task in mind."

    /**
     * Build the PREAMBLE-mode layer: zero messages when everything is
     * empty, otherwise ONE USER/ASSISTANT pair carrying every non-empty
     * section concatenated under labelled headings. One pair (not one per
     * section) so role alternation stays trivial when the layer is
     * prepended to the history tail.
     */
    fun composePreamble(
        profile: String?,
        rules: List<RuleEntry>,
        task: TaskNotes?,
    ): List<Message> {
        val sections = renderSections(profile, rules, task)
        if (sections.isEmpty()) return emptyList()
        return listOf(
            Message(Role.USER, sections.joinToString("\n\n")),
            Message(Role.ASSISTANT, PREAMBLE_ACK),
        )
    }

    /**
     * Build the SYSTEM-mode layer: one `Role.SYSTEM` message per
     * non-empty section. Each provider concatenates them into its
     * native system slot per the `LlmApi` contract, so the on-wire
     * effect is one system block — but emitting separate messages here
     * keeps logging and tests legible.
     */
    fun composeSystem(
        profile: String?,
        rules: List<RuleEntry>,
        task: TaskNotes?,
    ): List<Message> = renderSections(profile, rules, task)
        .map { Message(Role.SYSTEM, it) }

    /**
     * Render whichever sections are non-empty. Order is fixed
     * (profile → rules → task) so the model sees them in the same
     * stable layering both modes; downstream tests assert on this
     * ordering.
     */
    private fun renderSections(
        profile: String?,
        rules: List<RuleEntry>,
        task: TaskNotes?,
    ): List<String> = buildList {
        profile?.takeIf { it.isNotBlank() }?.let {
            add("$PROFILE_HEADING\n${it.trim()}")
        }
        if (rules.isNotEmpty()) {
            val body = rules.joinToString("\n") { "- ${it.text.replace("\n", " ").trim()}" }
            add("$RULES_HEADING\n$body")
        }
        task?.let { renderTask(it) }?.let { add(it) }
    }

    private fun renderTask(task: TaskNotes): String? {
        val lines = buildList {
            task.goal?.takeIf { it.isNotBlank() }?.let { add("Goal: ${it.trim()}") }
            task.stage?.takeIf { it.isNotBlank() }?.let { add("Stage: ${it.trim()}") }
            if (task.notes.isNotEmpty()) {
                add("Notes:")
                task.notes.forEach { add("- ${it.trim()}") }
            }
        }
        if (lines.isEmpty()) return null
        return "$TASK_HEADING (${task.taskId})\n" + lines.joinToString("\n")
    }
}

package ru.den.writes.code.project01.shared.memory

import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role

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
object MemoryLayer {
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
        profile: ProfileData?,
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
     * non-empty section (profile is one section, not four sub-messages
     * — sub-sections live inside the profile block). Each provider
     * concatenates them into its native system slot per the `LlmApi`
     * contract, so the on-wire effect is one system block — but
     * emitting separate messages here keeps logging and tests legible.
     */
    fun composeSystem(
        profile: ProfileData?,
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
        profile: ProfileData?,
        rules: List<RuleEntry>,
        task: TaskNotes?,
    ): List<String> = buildList {
        profile?.let(::buildProfileBlock)?.let { add(it) }
        if (rules.isNotEmpty()) {
            val body = rules.joinToString("\n") { "- ${it.text.replace("\n", " ").trim()}" }
            add("$RULES_HEADING\n$body")
        }
        task?.let { renderTask(it) }?.let { add(it) }
    }

    /**
     * Format a [ProfileData] as the wire `[Profile]` block:
     *
     * - `freeText` (if any) sits directly under the heading — so a
     *   profile that only has free text renders as `[Profile]\n<text>`,
     *   byte-identical to the unstructured wire shape.
     * - Each non-empty section follows as `Style:` / `Format:` /
     *   `Constraints:` / `Context:` with `- bullets` underneath.
     * - Empty profile → null so the whole block is omitted.
     */
    private fun buildProfileBlock(data: ProfileData): String? {
        if (data.isEmpty()) return null
        return buildString {
            append(PROFILE_HEADING)
            data.freeText?.takeIf { it.isNotBlank() }?.let {
                append('\n')
                append(it.trim())
            }
            for (section in ProfileSection.entries) {
                val items = data.items(section)
                if (items.isEmpty()) continue
                append('\n')
                append(section.displayName)
                append(':')
                for (item in items) {
                    append('\n')
                    append("- ")
                    append(item)
                }
            }
        }
    }

    private fun renderTask(task: TaskNotes): String? {
        val lines = buildList {
            task.goal?.takeIf { it.isNotBlank() }?.let { add("Goal: ${it.trim()}") }
            task.stage?.let { stage ->
                add("Stage: ${stage.keyword}")
                add("Status: ${if (task.paused) "paused" else "active"}")
                add("Expected action: ${stage.expectedAction}")
                // The auto-advance protocol: when not paused, tell the model
                // which stages it may move to and how to signal a move. The
                // agent reads the [[stage:<next>]] marker back, validates it
                // against the same table, and advances. Suppressed while paused
                // (we're deliberately holding) and at a terminal stage.
                val next = TaskStateMachine.allowedNext(stage)
                if (!task.paused && next.isNotEmpty()) {
                    add("Allowed next: ${next.joinToString(", ") { it.keyword }}")
                    add(
                        "When this stage is complete, end your reply with a line " +
                            "[[stage:<next>]] choosing one allowed-next stage; do not skip stages."
                    )
                }
            }
            if (task.notes.isNotEmpty()) {
                add("Notes:")
                task.notes.forEach { add("- ${it.trim()}") }
            }
        }
        if (lines.isEmpty()) return null
        return "$TASK_HEADING (${task.taskId})\n" + lines.joinToString("\n")
    }
}

package ru.den.writes.code.project01.shared.memory

/**
 * One short-text entry from the long-term `rules/` layer.
 *
 * [id] is the three-digit prefix of the on-disk file name (e.g. "001"
 * for `001-kotlin-only.md`) and is what users pass to `-memory rule rm`.
 * [text] is the rule body verbatim (single-line in the typical case;
 * multi-line is tolerated).
 */
data class RuleEntry(val id: String, val text: String)

/**
 * Parsed working-memory file for one task.
 *
 * Mirrors the `# Task: <id>` markdown shape on disk. All section fields
 * are optional so a freshly-created task can grow incrementally — empty
 * goal/stage are rendered as no section at all, not `## Goal\n` with a
 * blank body.
 */
data class TaskNotes(
    val taskId: String,
    val goal: String? = null,
    val stage: String? = null,
    val notes: List<String> = emptyList(),
)

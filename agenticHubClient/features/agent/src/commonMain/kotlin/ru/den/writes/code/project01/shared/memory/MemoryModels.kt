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
 * Mirrors the `# Task: <id>` markdown shape on disk. All fields except
 * [taskId] are optional so a freshly-created task can grow incrementally —
 * an empty goal is rendered as no section at all, not `## Goal\n` with a
 * blank body.
 *
 * [stage] is the task's finite-state-machine position (see [TaskStage]);
 * null means "no stage set yet" — a legacy or hand-edited file. [paused] is
 * an orthogonal flag (pause is allowed at any stage); while it's true the
 * agent holds the stage instead of auto-advancing.
 */
data class TaskNotes(
    val taskId: String,
    val goal: String? = null,
    val stage: TaskStage? = null,
    val paused: Boolean = false,
    val notes: List<String> = emptyList(),
)

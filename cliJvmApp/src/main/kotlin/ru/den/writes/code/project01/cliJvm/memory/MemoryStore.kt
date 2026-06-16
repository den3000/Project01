package ru.den.writes.code.project01.cliJvm.memory

import java.io.File

/**
 * One short-text entry from the long-term `rules/` layer.
 *
 * [id] is the three-digit prefix of the on-disk file name (e.g. "001"
 * for `001-kotlin-only.md`) and is what users pass to `-memory rule rm`.
 * [text] is the rule body verbatim (single-line in the typical case;
 * multi-line is tolerated).
 */
internal data class RuleEntry(val id: String, val text: String)

/**
 * Parsed working-memory file for one task.
 *
 * Mirrors the `# Task: <id>` markdown shape on disk. All section fields
 * are optional so a freshly-created task can grow incrementally — empty
 * goal/stage are rendered as no section at all, not `## Goal\n` with a
 * blank body.
 */
internal data class TaskNotes(
    val taskId: String,
    val goal: String? = null,
    val stage: String? = null,
    val notes: List<String> = emptyList(),
)

/**
 * Reads and writes the two new memory layers — long-term (profile +
 * rules) and working (per-task notes) — under [root]. Short-term memory
 * (chat history, summaries, sticky facts) is owned by `HistoryStore` and
 * lives in the Room database, untouched by this class.
 *
 * Layout under [root]:
 *
 * ```
 * <root>/
 * ├── profile.md
 * ├── rules/
 * │   ├── 001-<slug>.md
 * │   └── 002-<slug>.md
 * └── tasks/
 *     └── <taskId>.md
 * ```
 *
 * The store is intentionally cache-less: every read hits disk so users
 * can edit files between turns and see the change immediately. Files are
 * plain markdown — anyone can `cat`/`vim` them outside the CLI.
 */
internal class MemoryStore(private val root: File) {
    init {
        root.mkdirs()
        rulesDir.mkdirs()
        tasksDir.mkdirs()
    }

    /** Returns the on-disk file contents trimmed; null if absent or blank. */
    fun loadProfile(): String? = profileFile
        .takeIf { it.exists() }
        ?.readText(Charsets.UTF_8)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /** Overwrites `profile.md` with [text] (trimmed). Blank deletes the file. */
    fun saveProfile(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            profileFile.delete()
        } else {
            profileFile.writeText(trimmed + "\n", Charsets.UTF_8)
        }
    }

    /**
     * List rules from `rules/` in ascending id order. File-name shape:
     * `NNN-<slug>.md` (three-digit id + dash + slug). Anything not
     * matching that shape is silently skipped — keeps stray notes from
     * the user's text editor out of the prompt.
     */
    fun listRules(): List<RuleEntry> = rulesDir.listFiles()
        ?.asSequence()
        ?.mapNotNull { file ->
            val match = RULE_FILE_NAME.matchEntire(file.name) ?: return@mapNotNull null
            val id = match.groupValues[1]
            val text = file.readText(Charsets.UTF_8).trim()
            if (text.isEmpty()) null else RuleEntry(id, text)
        }
        ?.sortedBy { it.id }
        ?.toList()
        ?: emptyList()

    /**
     * Append a new rule. Id is the smallest free three-digit number
     * (max-existing + 1, so gaps from `removeRule` aren't reused — keeps
     * old references stable). Slug is a best-effort ASCII slugification
     * of [text], capped at 40 chars; if the text contains nothing the
     * slug regex accepts, the file falls back to `NNN-rule.md`.
     */
    fun addRule(text: String): RuleEntry {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "rule text must not be blank" }
        val existing = listRules().mapNotNull { it.id.toIntOrNull() }
        val nextNumber = (existing.maxOrNull() ?: 0) + 1
        val id = NUMBER_FORMAT.format(nextNumber)
        val slug = slugify(trimmed).ifEmpty { "rule" }
        File(rulesDir, "$id-$slug.md").writeText(trimmed + "\n", Charsets.UTF_8)
        return RuleEntry(id, trimmed)
    }

    /**
     * Delete the rule with the given [id]. Returns true if a file was
     * removed; false if no rule with that id existed (the caller decides
     * how loud to be about it).
     */
    fun removeRule(id: String): Boolean {
        val file = rulesDir.listFiles()
            ?.firstOrNull { RULE_FILE_NAME.matchEntire(it.name)?.groupValues?.get(1) == id }
            ?: return false
        return file.delete()
    }

    /** All task ids that have a `tasks/<id>.md` file, sorted alphabetically. */
    fun listTaskIds(): List<String> = tasksDir.listFiles()
        ?.asSequence()
        ?.filter { it.isFile && it.name.endsWith(".md") }
        ?.map { it.nameWithoutExtension }
        ?.sorted()
        ?.toList()
        ?: emptyList()

    /** Parse `tasks/<taskId>.md`; null if the file is absent. */
    fun loadTask(taskId: String): TaskNotes? {
        val file = taskFile(taskId)
        if (!file.exists()) return null
        return parseTaskNotes(taskId, file.readText(Charsets.UTF_8))
    }

    /** Overwrite `tasks/<taskId>.md` from [notes]. */
    fun saveTask(notes: TaskNotes) {
        taskFile(notes.taskId).writeText(renderTaskNotes(notes), Charsets.UTF_8)
    }

    /**
     * Append [note] to the current task's Notes section. Creates the
     * file (with only the new note) if it didn't exist — so the typical
     * REPL flow of "select a task, drop a note in it" doesn't trip over
     * a missing file.
     */
    fun appendTaskNote(taskId: String, note: String) {
        val trimmed = note.trim()
        require(trimmed.isNotEmpty()) { "task note must not be blank" }
        val existing = loadTask(taskId) ?: TaskNotes(taskId)
        saveTask(existing.copy(notes = existing.notes + trimmed))
    }

    private val profileFile: File get() = File(root, PROFILE_FILE_NAME)
    private val rulesDir: File get() = File(root, RULES_DIR)
    private val tasksDir: File get() = File(root, TASKS_DIR)
    private fun taskFile(taskId: String): File = File(tasksDir, "$taskId.md")

    companion object {
        const val PROFILE_FILE_NAME: String = "profile.md"
        const val RULES_DIR: String = "rules"
        const val TASKS_DIR: String = "tasks"

        private val RULE_FILE_NAME = Regex("^(\\d{3})-[a-z0-9-]+\\.md$")
        private const val SLUG_MAX_LENGTH = 40
        private const val NUMBER_FORMAT = "%03d"

        /**
         * Lowercase + collapse-non-ASCII-alphanum-to-dash + trim dashes +
         * cap at 40 chars. Cyrillic and other non-ASCII characters become
         * dashes; the rule body keeps its original text — only the file
         * NAME is mangled. Returns "" if nothing usable remains; caller
         * substitutes a fallback.
         */
        internal fun slugify(text: String): String {
            val ascii = text.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            return if (ascii.length <= SLUG_MAX_LENGTH) ascii
            else ascii.substring(0, SLUG_MAX_LENGTH).trimEnd('-')
        }

        /**
         * Parse the structured task-notes markdown back into [TaskNotes].
         * Permissive on shape: missing sections become null/empty;
         * extra/unknown sections are silently dropped (forward compat
         * for fields a future demo might add).
         */
        internal fun parseTaskNotes(taskId: String, raw: String): TaskNotes {
            val sections = mutableMapOf<String, String>()
            var current: String? = null
            val body = StringBuilder()
            for (line in raw.lines()) {
                val header = SECTION_HEADER.matchEntire(line)
                if (header != null) {
                    current?.let { sections[it] = body.toString().trim() }
                    body.setLength(0)
                    current = header.groupValues[1].trim().lowercase()
                } else if (current != null) {
                    body.appendLine(line)
                }
            }
            current?.let { sections[it] = body.toString().trim() }
            val notes = sections["notes"]
                ?.lines()
                ?.mapNotNull { it.trim().removePrefix("-").trim().takeIf(String::isNotEmpty) }
                .orEmpty()
            return TaskNotes(
                taskId = taskId,
                goal = sections["goal"]?.takeIf { it.isNotEmpty() },
                stage = sections["stage"]?.takeIf { it.isNotEmpty() },
                notes = notes,
            )
        }

        /**
         * Render [TaskNotes] into the canonical on-disk shape. Empty
         * sections are omitted so a `cat` of the file doesn't show
         * `## Goal\n\n## Stage\n…` placeholders.
         */
        internal fun renderTaskNotes(notes: TaskNotes): String = buildString {
            appendLine("# Task: ${notes.taskId}")
            notes.goal?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("## Goal")
                appendLine(it.trim())
            }
            notes.stage?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("## Stage")
                appendLine(it.trim())
            }
            if (notes.notes.isNotEmpty()) {
                appendLine()
                appendLine("## Notes")
                notes.notes.forEach { appendLine("- ${it.trim()}") }
            }
        }

        private val SECTION_HEADER = Regex("^##\\s+(.+?)\\s*$")
    }
}

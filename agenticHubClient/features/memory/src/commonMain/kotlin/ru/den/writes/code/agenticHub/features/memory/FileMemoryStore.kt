package ru.den.writes.code.agenticHub.features.memory

import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.localFileSystem

/**
 * File-backed [MemoryStore]: reads and writes the long-term (profile +
 * rules) and working (per-task notes) memory layers under [root]. Short-term
 * memory (chat history, summaries, sticky facts) is owned by `HistoryStore`
 * and lives in the Room database, untouched by this class.
 *
 * All I/O goes through the injected [LocalFileSystem] port (defaulting to the
 * platform's [localFileSystem]) — no direct file-type dependency, so the store
 * itself is multiplatform. [root] is a path string; the layout under it:
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
public class FileMemoryStore(
    private val root: String,
    private val fs: LocalFileSystem = localFileSystem(),
) : MemoryStore {
    init {
        fs.mkdirs(root)
        fs.mkdirs(rulesDir)
        fs.mkdirs(tasksDir)
        fs.mkdirs(profilesDir)
    }

    /** Returns the on-disk file contents trimmed; null if absent or blank. */
    override fun loadProfile(): String? = fs.readText(profileFile)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /** Overwrites `profile.md` with [text] (trimmed). Blank deletes the file. */
    override fun saveProfile(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            fs.delete(profileFile)
        } else {
            fs.writeText(profileFile, trimmed + "\n")
        }
    }

    /**
     * Structured view of the profile. Returns null when the underlying
     * file is missing or — after parse — turns out empty. Free-form
     * profiles come back as `ProfileData(freeText = …)`; nothing else fires.
     */
    override fun loadProfileData(): ProfileData? {
        val raw = loadProfile() ?: return null
        val data = parseProfileData(raw)
        return data.takeUnless { it.isEmpty() }
    }

    /**
     * Persist [data] back to `profile.md`. An empty [ProfileData] deletes
     * the file (via [saveProfile]) so a `cat profile.md` after `clear`
     * doesn't show a stub.
     */
    override fun saveProfileData(data: ProfileData) {
        saveProfile(renderProfileData(data))
    }

    /**
     * Append [text] under [section]. Reads the current profile, mutates
     * via [ProfileData.addItem], writes back. Returns the new state so
     * callers can echo it without re-reading the disk.
     */
    override fun addProfileItem(section: ProfileSection, text: String): ProfileData {
        val updated = (loadProfileData() ?: ProfileData()).addItem(section, text)
        saveProfileData(updated)
        return updated
    }

    /** Empty just [section]; other sections and `freeText` survive. */
    override fun clearProfileSection(section: ProfileSection): ProfileData {
        val updated = (loadProfileData() ?: ProfileData()).clear(section)
        saveProfileData(updated)
        return updated
    }

    /** Drop the entire profile, including any legacy `freeText`. */
    override fun clearProfile() {
        saveProfile("")
    }

    // --- Named profiles --------------------------------------------
    //
    // Lives next to `profile.md` (the unnamed default-fallback) under
    // `profiles/<name>.md`. Same shape as `tasks/<id>.md`: one file per
    // named profile, plain markdown the user can `cat`/`vim` outside
    // the CLI. The unnamed `profile.md` is still served by the
    // `loadProfileData`/`saveProfileData` family above and acts as the
    // fallback when no named profile is active.

    /** Profile names that have a `profiles/<name>.md` file, sorted. */
    override fun listProfileNames(): List<String> = fs.listFileNames(profilesDir)
        .filter { it.endsWith(".md") }
        .map { it.removeSuffix(".md") }
        .sorted()

    /** Parse `profiles/<name>.md`; null if the file is absent / blank. */
    override fun loadNamedProfile(name: String): ProfileData? {
        val raw = fs.readText(namedProfileFile(name))?.trim() ?: return null
        if (raw.isEmpty()) return null
        return parseProfileData(raw).takeUnless { it.isEmpty() }
    }

    /**
     * Overwrite `profiles/<name>.md` from [data]. An empty [ProfileData]
     * deletes the file — touch-create stays via [touchNamedProfile].
     */
    override fun saveNamedProfile(name: String, data: ProfileData) {
        val rendered = renderProfileData(data).trim()
        if (rendered.isEmpty()) {
            fs.delete(namedProfileFile(name))
        } else {
            fs.writeText(namedProfileFile(name), rendered + "\n")
        }
    }

    /** Append [text] under [section] in `profiles/<name>.md`. */
    override fun addNamedProfileItem(name: String, section: ProfileSection, text: String): ProfileData {
        val updated = (loadNamedProfile(name) ?: ProfileData()).addItem(section, text)
        saveNamedProfile(name, updated)
        return updated
    }

    /** Empty just [section] in `profiles/<name>.md`; other sections survive. */
    override fun clearNamedProfileSection(name: String, section: ProfileSection): ProfileData {
        val updated = (loadNamedProfile(name) ?: ProfileData()).clear(section)
        saveNamedProfile(name, updated)
        return updated
    }

    /** Delete `profiles/<name>.md`; returns true iff a file was removed. */
    override fun clearNamedProfile(name: String): Boolean = fs.delete(namedProfileFile(name))

    /**
     * Delete every named profile AND the unnamed default `profile.md`. Returns
     * how many named profiles were removed (the unnamed one is cleared
     * regardless). Backs bare `profile clear` — the "nuke all profiles" form.
     */
    override fun clearAllProfiles(): Int {
        val removed = fs.listFileNames(profilesDir)
            .filter { it.endsWith(".md") }
            .count { fs.delete("$profilesDir/$it") }
        fs.delete(profileFile)
        return removed
    }

    /**
     * Create an empty `profiles/<name>.md` if it doesn't exist yet. Used
     * by `/profile-use <name>` so a fresh profile shows up in
     * [listProfileNames] before the first bullet is added.
     */
    override fun touchNamedProfile(name: String) {
        if (!fs.exists(namedProfileFile(name))) fs.writeText(namedProfileFile(name), "")
    }

    private fun namedProfileFile(name: String): String = "$profilesDir/$name.md"

    /**
     * List rules from `rules/` in ascending id order. File-name shape:
     * `NNN-<slug>.md` (three-digit id + dash + slug). Anything not
     * matching that shape is silently skipped — keeps stray notes from
     * the user's text editor out of the prompt.
     */
    override fun listRules(): List<RuleEntry> = fs.listFileNames(rulesDir)
        .mapNotNull { name ->
            val match = RULE_FILE_NAME.matchEntire(name) ?: return@mapNotNull null
            val text = fs.readText("$rulesDir/$name")?.trim().orEmpty()
            if (text.isEmpty()) null else RuleEntry(match.groupValues[1], text)
        }
        .sortedBy { it.id }

    /**
     * Append a new rule. Id is the smallest free three-digit number
     * (max-existing + 1, so gaps from [removeRule] aren't reused — keeps
     * old references stable). Slug is a best-effort ASCII slugification
     * of [text], capped at 40 chars; if the text contains nothing the
     * slug regex accepts, the file falls back to `NNN-rule.md`.
     */
    override fun addRule(text: String): RuleEntry {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "rule text must not be blank" }
        val existing = listRules().mapNotNull { it.id.toIntOrNull() }
        val nextNumber = (existing.maxOrNull() ?: 0) + 1
        val id = numberId(nextNumber)
        val slug = slugify(trimmed).ifEmpty { "rule" }
        fs.writeText("$rulesDir/$id-$slug.md", trimmed + "\n")
        return RuleEntry(id, trimmed)
    }

    /**
     * Delete the rule with the given [id]. Returns true if a file was
     * removed; false if no rule with that id existed (the caller decides
     * how loud to be about it).
     */
    override fun removeRule(id: String): Boolean {
        val name = fs.listFileNames(rulesDir)
            .firstOrNull { RULE_FILE_NAME.matchEntire(it)?.groupValues?.get(1) == id }
            ?: return false
        return fs.delete("$rulesDir/$name")
    }

    /** Delete every rule file. Returns how many were removed. Backs bare `rule clear`. */
    override fun clearRules(): Int = fs.listFileNames(rulesDir)
        .filter { RULE_FILE_NAME.matchEntire(it) != null }
        .count { fs.delete("$rulesDir/$it") }

    /** All task ids that have a `tasks/<id>.md` file, sorted alphabetically. */
    override fun listTaskIds(): List<String> = fs.listFileNames(tasksDir)
        .filter { it.endsWith(".md") }
        .map { it.removeSuffix(".md") }
        .sorted()

    /** Parse `tasks/<taskId>.md`; null if the file is absent. */
    override fun loadTask(taskId: String): TaskNotes? {
        val raw = fs.readText(taskFile(taskId)) ?: return null
        return parseTaskNotes(taskId, raw)
    }

    /** Overwrite `tasks/<taskId>.md` from [notes]. */
    override fun saveTask(notes: TaskNotes) {
        fs.writeText(taskFile(notes.taskId), renderTaskNotes(notes))
    }

    /**
     * Append [note] to the current task's Notes section. Creates the
     * file (with only the new note) if it didn't exist — so the typical
     * REPL flow of "select a task, drop a note in it" doesn't trip over
     * a missing file.
     */
    override fun appendTaskNote(taskId: String, note: String) {
        val trimmed = note.trim()
        require(trimmed.isNotEmpty()) { "task note must not be blank" }
        val existing = loadTask(taskId) ?: TaskNotes(taskId)
        saveTask(existing.copy(notes = existing.notes + trimmed))
    }

    /** Delete `tasks/<taskId>.md`; returns true iff a file was removed. */
    override fun deleteTask(taskId: String): Boolean = fs.delete(taskFile(taskId))

    /** Delete every task file. Returns how many were removed. Backs bare `task clear`. */
    override fun clearTasks(): Int = fs.listFileNames(tasksDir)
        .filter { it.endsWith(".md") }
        .count { fs.delete("$tasksDir/$it") }

    private val profileFile: String get() = "$root/$PROFILE_FILE_NAME"
    private val rulesDir: String get() = "$root/$RULES_DIR"
    private val tasksDir: String get() = "$root/$TASKS_DIR"
    private val profilesDir: String get() = "$root/$PROFILES_DIR"
    private fun taskFile(taskId: String): String = "$tasksDir/$taskId.md"

    public companion object {
        public const val PROFILE_FILE_NAME: String = "profile.md"
        public const val RULES_DIR: String = "rules"
        public const val TASKS_DIR: String = "tasks"
        public const val PROFILES_DIR: String = "profiles"

        private val RULE_FILE_NAME = Regex("^(\\d{3})-[a-z0-9-]+\\.md$")
        private const val SLUG_MAX_LENGTH = 40

        /** Zero-pad [n] to a three-digit rule id (`%03d` без java.util.Formatter). */
        private fun numberId(n: Int): String = n.toString().padStart(3, '0')

        /**
         * Lowercase + collapse-non-ASCII-alphanum-to-dash + trim dashes +
         * cap at 40 chars. Cyrillic and other non-ASCII characters become
         * dashes; the rule body keeps its original text — only the file
         * NAME is mangled. Returns "" if nothing usable remains; caller
         * substitutes a fallback.
         */
        public fun slugify(text: String): String {
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
        public fun parseTaskNotes(taskId: String, raw: String): TaskNotes {
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
                // Unknown / legacy free-text stage → null (tolerant): the
                // task simply has no FSM position until one is set.
                stage = sections["stage"]?.let { TaskStage.byKeyword(it) },
                paused = sections["paused"]?.trim()?.lowercase() == "true",
                notes = notes,
            )
        }

        /**
         * Render [TaskNotes] into the canonical on-disk shape. Empty
         * sections are omitted so a `cat` of the file doesn't show
         * `## Goal\n\n## Stage\n…` placeholders.
         */
        public fun renderTaskNotes(notes: TaskNotes): String = buildString {
            appendLine("# Task: ${notes.taskId}")
            notes.goal?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("## Goal")
                appendLine(it.trim())
            }
            notes.stage?.let {
                appendLine()
                appendLine("## Stage")
                appendLine(it.keyword)
            }
            if (notes.paused) {
                appendLine()
                appendLine("## Paused")
                appendLine("true")
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

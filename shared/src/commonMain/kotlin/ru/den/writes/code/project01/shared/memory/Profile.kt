package ru.den.writes.code.project01.shared.memory

/**
 * One named slice of the user profile. The four sections cover the
 * standard "style / format / constraints / context" partition:
 *
 * - [STYLE] — tone, length, language ("кратко, русский").
 * - [FORMAT] — output shape ("code-first", "markdown bullets").
 * - [CONSTRAINTS] — stack/library bans the agent must honour
 *   ("Kotlin only", "no RxJava").
 * - [CONTEXT] — who the user is and what they want ("Android dev,
 *   learning KMP").
 *
 * [keyword] is the lowercase CLI/REPL identifier (e.g. `style` in
 * `/profile style …`). [displayName] is the colon-prefixed label rendered
 * inside the wire `[Profile]` block. [markdownHeading] is the on-disk
 * `## Style`-style heading used in `profile.md`.
 */
enum class ProfileSection(
    val keyword: String,
    val displayName: String,
    val markdownHeading: String,
) {
    STYLE("style", "Style", "## Style"),
    FORMAT("format", "Format", "## Format"),
    CONSTRAINTS("constraints", "Constraints", "## Constraints"),
    CONTEXT("context", "Context", "## Context"),
    ;

    companion object {
        fun byKeyword(token: String): ProfileSection? {
            val needle = token.lowercase()
            return entries.firstOrNull { it.keyword == needle }
        }
    }
}

/**
 * Structured view of `profile.md`. Each section is a list of short
 * bullet-style items; [freeText] holds any pre-section prose so a
 * free-form-blob profile keeps working — it's rendered above the
 * structured sections, byte-identical to the unstructured layout.
 *
 * The class is intentionally a plain data class with pure copy-based
 * mutators; the on-disk side lives in [MemoryStore].
 */
data class ProfileData(
    val style: List<String> = emptyList(),
    val format: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val context: List<String> = emptyList(),
    val freeText: String? = null,
) {
    fun isEmpty(): Boolean =
        style.isEmpty() && format.isEmpty() && constraints.isEmpty() &&
            context.isEmpty() && freeText.isNullOrBlank()

    fun items(section: ProfileSection): List<String> = when (section) {
        ProfileSection.STYLE -> style
        ProfileSection.FORMAT -> format
        ProfileSection.CONSTRAINTS -> constraints
        ProfileSection.CONTEXT -> context
    }

    fun withItems(section: ProfileSection, items: List<String>): ProfileData = when (section) {
        ProfileSection.STYLE -> copy(style = items)
        ProfileSection.FORMAT -> copy(format = items)
        ProfileSection.CONSTRAINTS -> copy(constraints = items)
        ProfileSection.CONTEXT -> copy(context = items)
    }

    /** Append [text] to [section]; blank/whitespace-only text is a no-op. */
    fun addItem(section: ProfileSection, text: String): ProfileData {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return this
        return withItems(section, items(section) + trimmed)
    }

    fun clear(section: ProfileSection): ProfileData = withItems(section, emptyList())
}

/**
 * Permissive parser for `profile.md`:
 *
 * - Text before the first `##` header is captured into `freeText`
 *   (preserves legacy free-form profiles verbatim).
 * - Headers are matched case-insensitively against the four section
 *   keywords. Anything else (`## SomethingElse`) is treated as an
 *   unknown section and silently dropped — forward compat for sections
 *   a future day might add.
 * - Inside a section, lines are stripped of leading `- ` / `* ` bullets
 *   and trimmed; blank lines are skipped; non-bullet lines are still
 *   accepted as single items so a casual `## Style\nкратко` works.
 */
fun parseProfileData(raw: String): ProfileData {
    if (raw.isBlank()) return ProfileData()

    val freeTextLines = mutableListOf<String>()
    val sectionItems = mutableMapOf<ProfileSection, MutableList<String>>()
    var sawHeader = false
    var currentSection: ProfileSection? = null

    for (line in raw.lines()) {
        val header = SECTION_HEADER.matchEntire(line.trim())
        if (header != null) {
            sawHeader = true
            currentSection = ProfileSection.byKeyword(header.groupValues[1])
            continue
        }
        if (!sawHeader) {
            freeTextLines.add(line)
            continue
        }
        val section = currentSection ?: continue
        val cleaned = BULLET_PREFIX.replaceFirst(line.trim(), "").trim()
        if (cleaned.isNotEmpty()) {
            sectionItems.getOrPut(section) { mutableListOf() }.add(cleaned)
        }
    }

    val freeText = freeTextLines.joinToString("\n").trim().takeIf { it.isNotEmpty() }
    return ProfileData(
        style = sectionItems[ProfileSection.STYLE].orEmpty().toList(),
        format = sectionItems[ProfileSection.FORMAT].orEmpty().toList(),
        constraints = sectionItems[ProfileSection.CONSTRAINTS].orEmpty().toList(),
        context = sectionItems[ProfileSection.CONTEXT].orEmpty().toList(),
        freeText = freeText,
    )
}

/**
 * Render [ProfileData] back into canonical markdown:
 *
 * - `freeText` (if present) sits at the top without a heading — so a
 *   free-text-only profile round-trips byte-for-byte.
 * - Then each non-empty section in the fixed [ProfileSection.entries]
 *   order, separated by a blank line, with bullets prefixed `- `.
 * - Empty [ProfileData] renders as `""` so `MemoryStore.saveProfile`
 *   deletes the file rather than leaving an empty stub on disk.
 */
fun renderProfileData(data: ProfileData): String {
    if (data.isEmpty()) return ""
    return buildString {
        data.freeText?.takeIf { it.isNotBlank() }?.let { appendLine(it.trim()) }
        for (section in ProfileSection.entries) {
            val items = data.items(section)
            if (items.isEmpty()) continue
            if (isNotEmpty()) appendLine()
            appendLine(section.markdownHeading)
            items.forEach { appendLine("- $it") }
        }
    }.trimEnd()
}

/**
 * Result of parsing a `profile …` sub-command — same shape for CLI
 * (`-memory profile …`) and REPL (`/profile …`). The CLI / REPL parsers
 * map each variant to their own typed action enum so the I/O layer stays
 * thin.
 *
 * [Append] with a blank `text` means the user typed a section keyword
 * but forgot the body (`profile style`); callers turn that into the
 * appropriate "missing argument" error.
 */
sealed interface ProfileCommand {
    data class Append(val section: ProfileSection, val text: String) : ProfileCommand
    data class ClearSection(val section: ProfileSection) : ProfileCommand
    data object ClearAll : ProfileCommand
    data class SetFreeText(val text: String) : ProfileCommand
}

/**
 * Parse a `profile …` body into one of [ProfileCommand]. Recognised
 * shapes:
 *
 * - `clear` → [ProfileCommand.ClearAll]
 * - `<section> clear` → [ProfileCommand.ClearSection]
 * - `<section> <text>` → [ProfileCommand.Append]
 * - anything else → [ProfileCommand.SetFreeText] (legacy free-text path:
 *   dumps the whole input into `profile.md` as free text)
 *
 * Returns null when [input] is blank — caller signals "needs an argument".
 */
fun parseProfileCommand(input: String): ProfileCommand? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    val tokens = trimmed.split(Regex("\\s+"))
    val first = tokens[0]

    if (tokens.size == 1 && first.equals("clear", ignoreCase = true)) {
        return ProfileCommand.ClearAll
    }

    val section = ProfileSection.byKeyword(first)
        ?: return ProfileCommand.SetFreeText(trimmed)

    if (tokens.size == 1) return ProfileCommand.Append(section, "")

    if (tokens.size == 2 && tokens[1].equals("clear", ignoreCase = true)) {
        return ProfileCommand.ClearSection(section)
    }

    val text = trimmed.substring(first.length).trim()
    return ProfileCommand.Append(section, text)
}

/**
 * Named-profile name validation — shared between CLI (`-profile`,
 * `-memory profile <name> …`) and REPL (`/profile-use`, `/profile <name>`).
 * Matches the same alphabet as session ids and task ids, capped at 64
 * characters so file names stay reasonable.
 */
fun isValidProfileName(s: String): Boolean =
    s.isNotEmpty() && s.length <= PROFILE_NAME_MAX_LENGTH && s.matches(PROFILE_NAME_REGEX)

const val PROFILE_NAME_MAX_LENGTH: Int = 64
private val PROFILE_NAME_REGEX = Regex("^[a-zA-Z0-9_-]+$")

private val SECTION_HEADER = Regex("^##\\s+(\\w+).*$")
private val BULLET_PREFIX = Regex("^[-*]\\s*")

package ru.den.writes.code.agenticHub.mcps.projectfs

/** Longer lines are cut with a marker: one minified file shouldn't eat a whole result. */
internal const val MAX_LINE_CHARS = 300

/** The `  45| ` gutter [ProjectReader] prints in front of every line it returns. */
private val LINE_NUMBER_GUTTER = Regex("""^\s*\d+\|\s?""")

/**
 * Lines as a reader counts them: a trailing newline terminates the last line rather than
 * starting an empty one, so `"a\nb\n"` is 2 — matching what an editor shows.
 */
internal fun String.countLines(): Int = when {
    isEmpty() -> 0
    else -> count { it == '\n' } + if (endsWith("\n")) 0 else 1
}

/** Split on `\n` with the same trailing-newline rule as [countLines]. */
internal fun String.toDisplayLines(): List<String> = when {
    isEmpty() -> emptyList()
    endsWith("\n") -> split("\n").dropLast(1)
    else -> split("\n")
}

/**
 * Whether the text reads as binary — a NUL in the opening stretch.
 *
 * Sniffed on the loaded text rather than on a separate peek at the file: the guard that
 * matters (size) already ran, so the bytes are here, and a second open would cost a pass
 * over the same file for one byte's worth of answer.
 */
internal fun String.looksBinary(): Boolean = take(BINARY_SNIFF_BYTES).any { it.code == 0 }

/** Cut an over-long line and say how much was dropped. */
internal fun String.clipLine(): String =
    if (length <= MAX_LINE_CHARS) this else take(MAX_LINE_CHARS) + "…(+${length - MAX_LINE_CHARS})"

/** Non-overlapping occurrences of [needle]. */
internal fun String.countOccurrences(needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var index = indexOf(needle)
    while (index >= 0) {
        count++
        index = indexOf(needle, index + needle.length)
    }
    return count
}

/**
 * Drop the line-number gutter [ProjectReader] prints, but only when *every* non-blank
 * line carries one.
 *
 * Models copy `old` straight out of a read, gutter and all, and a mismatch costs a whole
 * tool round. The all-or-nothing condition is what keeps the normalisation safe: a
 * genuine line that happens to look like `42| foo` survives untouched unless its
 * neighbours are numbered too.
 */
internal fun String.stripLineNumbers(): String {
    val lines = split("\n")
    val meaningful = lines.filter { it.isNotBlank() }
    if (meaningful.isEmpty() || !meaningful.all { LINE_NUMBER_GUTTER.containsMatchIn(it) }) return this
    return lines.joinToString("\n") { it.replaceFirst(LINE_NUMBER_GUTTER, "") }
}

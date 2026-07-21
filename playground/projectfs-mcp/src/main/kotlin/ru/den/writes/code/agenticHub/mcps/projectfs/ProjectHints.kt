package ru.den.writes.code.agenticHub.mcps.projectfs

/** Leading characters of a needle used to point at a near-miss line. */
private const val PROBE_CHARS = 24

/** Below this a probe matches too much to be a useful hint. */
internal const val MIN_PROBE_CHARS = 4

/** Words probed when a multi-word search comes back empty. */
internal const val MAX_PROBE_WORDS = 4

/**
 * What a tool says when it found nothing.
 *
 * A refusal that only reports failure costs a round and teaches nothing — twice over this
 * turned out to be the difference between an assistant that keeps working and one that
 * stops: an empty search read as "no discrepancies exist", and a failed replacement sent
 * the model guessing at the text. So each hint here names the *next call*.
 *
 * These are pure functions of text. They depend on neither [FileIo] nor [ProjectPaths],
 * which is what makes the hint wording — the part that actually steers a model — cheap to
 * pin down in tests.
 */

/**
 * The individual words worth probing when a query comes back empty, or nothing when the
 * query is a single term (there is no decomposition to suggest).
 */
internal fun probeWords(query: String): List<String> {
    val words = query.trim().split(WHITESPACE).filter { it.length >= MIN_PROBE_CHARS }
    return if (words.size < 2) emptyList() else words.take(MAX_PROBE_WORDS)
}

/**
 * Why a search came back empty, and what to try instead.
 *
 * A multi-word query is matched as one exact substring, but models phrase queries
 * naturally — "Compose 0.0.4-aurora" — and a document that says "переехал на
 * `0.0.4-aurora`" then yields nothing. Reporting which individual words *do* occur turns
 * a dead end into a direction.
 *
 * [hits] is how many files contain each of [probes]; it is collected during the search's
 * own pass, so the hint costs no extra walk over the tree.
 */
internal fun noMatchHint(query: String, subdir: String?, probes: List<String>, hits: List<Int>): String {
    val plain = "(совпадений нет: '$query'${subdir?.let { " под '$it'" } ?: ""})"
    if (probes.isEmpty()) return plain

    val present = probes.zip(hits).filter { (_, count) -> count > 0 }
    if (present.isEmpty()) {
        return "$plain\nОтдельные слова запроса тоже не встречаются — возьми другой термин."
    }

    val found = present.joinToString(", ") { (word, count) -> "'$word' — в $count файл(ах)" }
    return "(совпадений нет: '$query' — запрос ищется как одна точная подстрока целиком)\n" +
        "А по отдельности встречается: $found\n" +
        "Ищи по одному термину, а не фразой."
}

/**
 * Why a replacement missed, with the next call spelled out.
 *
 * The two probes cost nothing and between them cover how a mismatch actually happens: the
 * model reproduced the line but not its indentation, or it paraphrased. An error that
 * names the line to look at is worth more here than any amount of prompting.
 */
internal fun notFoundHint(rel: String, text: String, needle: String): String {
    val lines = text.toDisplayLines()
    val firstNeedleLine = needle.lineSequence().firstOrNull()?.trim().orEmpty()

    val sameButIndented = lines.indexOfFirst { it.trim() == firstNeedleLine && it != needle }
    if (sameButIndented >= 0) {
        return "projectfs error: 'old' не найден в '$rel'.\n" +
            "Строка ${sameButIndented + 1} совпадает по тексту, но отличается пробелами: " +
            "«${lines[sameButIndented].clipLine()}»\n" +
            rereadAdvice(rel, lineNumber = sameButIndented + 1)
    }

    val probe = firstNeedleLine.take(PROBE_CHARS)
    val similar = if (probe.length < MIN_PROBE_CHARS) -1 else lines.indexOfFirst { probe in it }
    if (similar >= 0) {
        return "projectfs error: 'old' не найден в '$rel'.\n" +
            "Похожая строка ${similar + 1}: «${lines[similar].clipLine()}»\n" +
            rereadAdvice(rel, lineNumber = similar + 1)
    }

    return "projectfs error: 'old' не найден в '$rel'. Перечитай нужный участок " +
        "read_project_file(path=\"$rel\") и передай текст дословно, без номеров строк."
}

/**
 * The exact call that puts the near-miss line back in front of the model, with a couple
 * of lines of lead-in. [lineNumber] is 1-based — the same number the message quotes, so
 * the window really does open two lines above the line named.
 */
private fun rereadAdvice(rel: String, lineNumber: Int): String =
    "Перечитай read_project_file(path=\"$rel\", offset=${(lineNumber - 2).coerceAtLeast(1)}, " +
        "limit=10) и повтори с точным текстом."

private val WHITESPACE = Regex("\\s+")

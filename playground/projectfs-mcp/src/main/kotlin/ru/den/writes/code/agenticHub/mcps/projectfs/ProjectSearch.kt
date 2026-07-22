package ru.den.writes.code.agenticHub.mcps.projectfs

/** Matches one search returns by default. */
internal const val SEARCH_MATCHES_DEFAULT = 80

/** Ceiling on matches per search. */
internal const val SEARCH_MATCHES_MAX = 200

/** Matches shown per file, so one dense file can't consume the whole search budget. */
internal const val MATCHES_PER_FILE = 25

/** Files listed in `filesOnly` mode before the result starts advising a narrower query. */
internal const val FILES_ONLY_LIMIT = 50

/**
 * `search_project_files`: lines matching a query across the project.
 *
 * `filesOnly` is the mode that makes wide questions affordable: "where is X used" over a
 * whole repository answers in a handful of lines instead of hundreds, so the model can map
 * the territory first and read only the places that matter.
 *
 * Matching is literal unless `regex`. That default is deliberate — Java's `\w` and `\b`
 * are ASCII-only, so a pattern like `\bЗадача\b` silently matches nothing.
 */
class ProjectSearch(
    private val paths: ProjectPaths,
    private val io: FileIo,
) {

    fun search(
        query: String,
        subdir: String? = null,
        ext: String? = null,
        regex: Boolean = false,
        ignoreCase: Boolean = false,
        filesOnly: Boolean = false,
        maxMatches: Int? = null,
    ): String {
        if (query.isBlank()) return "projectfs error: обязателен непустой 'query'"
        val compiled = if (!regex) {
            null
        } else {
            val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            runCatching { Regex(query, options) }.getOrElse { failure ->
                return "projectfs error: некорректный regex '$query': ${failure.message}"
            }
        }

        fun matches(line: String): Boolean =
            if (compiled != null) compiled.containsMatchIn(line) else line.contains(query, ignoreCase)

        // The words a failed search would suggest, counted during this same pass. Probing
        // them afterwards meant re-reading the whole corpus once per word — up to five
        // walks over the tree to explain one empty result.
        val probes = if (regex) emptyList() else probeWords(query)
        val probeHits = IntArray(probes.size)

        // Фильтр, не выбравший ни одного файла, обязан сказать об этом прямо: иначе
        // «совпадений нет» читается как «термина в проекте нет» — и уезжает в отчёт
        // как нарушение.
        io.emptySelectionHint(paths, subdir, ext)?.let { return it }

        val cap = (maxMatches ?: SEARCH_MATCHES_DEFAULT).coerceIn(1, SEARCH_MATCHES_MAX)
        val shown = mutableListOf<String>()
        val perFile = mutableListOf<Pair<String, Int>>()
        var total = 0

        for (rel in io.candidates(paths, subdir, ext)) {
            val absolute = paths.absoluteOf(rel)
            if (io.size(absolute) > LARGE_FILE_BYTES) continue
            val text = io.read(absolute) ?: continue
            if (text.looksBinary()) continue

            probes.forEachIndexed { index, word ->
                if (text.contains(word, ignoreCase)) probeHits[index]++
            }

            var inFile = 0
            text.toDisplayLines().forEachIndexed { index, line ->
                if (!matches(line)) return@forEachIndexed
                inFile++
                total++
                if (!filesOnly && inFile <= MATCHES_PER_FILE && shown.size < cap) {
                    shown += "$rel:${index + 1}: ${line.trim().clipLine()}"
                }
            }
            if (inFile > 0) perFile += rel to inFile
        }

        if (total == 0) return noMatchHint(query, subdir, probes, probeHits.toList())

        return if (filesOnly) renderFilesOnly(perFile, total) else renderMatches(shown, total)
    }

    /** `path (N)` per file — the map, not the territory. */
    private fun renderFilesOnly(perFile: List<Pair<String, Int>>, total: Int): String = buildString {
        perFile.take(FILES_ONLY_LIMIT).forEach { (rel, count) -> appendLine("$rel ($count)") }
        if (perFile.size > FILES_ONLY_LIMIT) {
            appendLine("… ещё ${perFile.size - FILES_ONLY_LIMIT} файл(ов)")
        }
        append("итого $total совпадени(й) в ${perFile.size} файл(ах)")
    }

    private fun renderMatches(shown: List<String>, total: Int): String = buildString {
        shown.forEach { appendLine(it) }
        if (total > shown.size) {
            append("… показано ${shown.size} из $total; сузь запрос, subdir или используй filesOnly=true")
        }
    }.trimEnd('\n')
}

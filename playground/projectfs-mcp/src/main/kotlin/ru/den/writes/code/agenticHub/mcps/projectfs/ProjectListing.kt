package ru.den.writes.code.agenticHub.mcps.projectfs

/** Paths one listing returns before it starts advising a narrower query. */
internal const val LIST_LIMIT_DEFAULT = 300

/**
 * `list_project_files`: the paths of a project, each with the size of its content.
 *
 * The line count is what earns its keep: seeing `README.md (155 lines)` tells the model
 * the file fits in a single read, so it doesn't spend a tool round finding out.
 */
class ProjectListing(
    private val paths: ProjectPaths,
    private val io: FileIo,
) {

    /**
     * Paths under [subdir] (whole project when null), optionally narrowed to [ext] (a
     * comma-separated extension list), one per line with a line count.
     */
    fun list(subdir: String? = null, ext: String? = null, limit: Int? = null): String {
        val cap = (limit ?: LIST_LIMIT_DEFAULT).coerceIn(1, LIST_LIMIT_DEFAULT)
        val all = io.candidates(paths, subdir, ext)

        if (all.isEmpty()) return io.emptySelectionHint(paths, subdir, ext) ?: emptyNotice(subdir, ext)

        val shown = all.take(cap)
        return buildString {
            // One stat per path, and it is one read: the listing needs the line count, the
            // byte size and the binary verdict together, and asking for them separately is
            // what made a 300-file listing cost 300 full reads.
            shown.forEach { rel -> appendLine("$rel (${describe(io.stat(paths.absoluteOf(rel)))})") }
            if (all.size > shown.size) {
                append("… показано ${shown.size} из ${all.size}; сузь subdir или ext")
            }
        }.trimEnd('\n')
    }

    /** `155 lines`, or `large, N KB` when the file is too big to be worth counting. */
    private fun describe(stat: FileStat?): String = when {
        stat == null -> "не прочитан"
        stat.lines == null -> "large, ${stat.bytes / 1024} KB"
        stat.lines == 1 -> "1 line"
        else -> "${stat.lines} lines"
    }

    private fun emptyNotice(subdir: String?, ext: String?): String =
        "(нет файлов${subdir?.let { " под '$it'" } ?: ""}${ext?.let { " с расширением $it" } ?: ""})"
}

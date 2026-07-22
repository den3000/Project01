package ru.den.writes.code.agenticHub.mcps.projectfs

/**
 * Lines returned by one read. Sized to clear in a single call the files this server exists
 * to reconcile — not just the docs (a 155-line README, a 163-line AGENTS.md) but the source
 * files a harder task reads whole (a 300-line screen, a viewmodel) — so the common case
 * costs one tool round instead of a paging chain that burns the turn's tool budget.
 */
internal const val READ_LIMIT_DEFAULT = 400

/** Ceiling on a single read — past this the result crowds out everything else in the context. */
internal const val READ_LIMIT_MAX = 1_000

/**
 * `read_project_file`: a window of one file as numbered lines.
 *
 * Numbers are here so the model can cite `path:line` and aim a later replacement. When
 * the window doesn't reach the end, the footer spells out the exact next call — that
 * saves a tool round the model would otherwise spend guessing how to continue.
 */
class ProjectReader(
    private val paths: ProjectPaths,
    private val io: FileIo,
) {

    fun read(path: String, offset: Int? = null, limit: Int? = null): String {
        val resolved = when (val candidate = paths.resolveRead(path)) {
            is ProjectPaths.Resolved.Denied -> return "projectfs error: ${candidate.reason}"
            is ProjectPaths.Resolved.Ok -> candidate
        }

        // Size before read: it is a stat syscall, and it refuses a 50 MB blob without
        // loading it. Everything else — the line count, the binary verdict — comes off
        // the one read that follows, so the file is never walked twice.
        val bytes = io.size(resolved.absolute)
        if (bytes > LARGE_FILE_BYTES) {
            return "projectfs error: '${resolved.rel}' слишком большой (${bytes / 1024} KB) — " +
                "он не поместится в контекст; используй search_project_files"
        }

        val text = io.read(resolved.absolute)
            ?: return "projectfs error: '${resolved.rel}' не найден или нечитаем"
        if (text.looksBinary()) {
            return "projectfs error: '${resolved.rel}' выглядит бинарным ($bytes bytes)"
        }
        val lines = text.toDisplayLines()
        if (lines.isEmpty()) return "${resolved.rel} (пустой файл)"

        val from = (offset ?: 1).coerceAtLeast(1)
        if (from > lines.size) {
            return "projectfs error: в '${resolved.rel}' ${lines.size} стр., offset=$from за пределами"
        }
        val count = (limit ?: READ_LIMIT_DEFAULT).coerceIn(1, READ_LIMIT_MAX)
        val to = minOf(from + count - 1, lines.size)
        val width = to.toString().length

        return buildString {
            appendLine("${resolved.rel} (lines $from-$to of ${lines.size})")
            (from..to).forEach { number ->
                val gutter = number.toString().padStart(width)
                // Only a blank line loses its separator space. Trimming a non-empty line
                // would drop trailing whitespace that is sometimes load-bearing — two of
                // them end a markdown line — and the model needs the text verbatim to
                // build an `old` that matches on replacement.
                val body = lines[number - 1].clipLine()
                appendLine(if (body.isEmpty()) "$gutter|" else "$gutter| $body")
            }
            if (to < lines.size) {
                append(
                    "… файл длиннее: продолжить read_project_file(path=\"${resolved.rel}\", " +
                        "offset=${to + 1}, limit=$count)",
                )
            }
        }.trimEnd('\n')
    }
}

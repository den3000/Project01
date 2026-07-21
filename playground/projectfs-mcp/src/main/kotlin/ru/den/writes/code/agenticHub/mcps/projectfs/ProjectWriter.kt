package ru.den.writes.code.agenticHub.mcps.projectfs

/**
 * `write_project_file` and `replace_in_project_file`: the two ways the assistant changes
 * the tree, each answering with the unified diff of what it did.
 */
class ProjectWriter(
    private val paths: ProjectPaths,
    private val io: FileIo,
) {

    /**
     * Write [content] to [path], creating the file if needed.
     *
     * Meant for files the assistant authors — a report, an ADR, a changelog. Rewriting an
     * existing document this way costs its whole text twice over (once as the argument,
     * once in the transcript); [replace] is the tool for editing one.
     */
    fun write(path: String, content: String): String {
        val resolved = when (val candidate = paths.resolveWrite(path)) {
            is ProjectPaths.Resolved.Denied -> return "projectfs error: ${candidate.reason}"
            is ProjectPaths.Resolved.Ok -> candidate
        }
        val existed = io.exists(resolved.absolute)
        val before = if (existed) io.read(resolved.absolute).orEmpty() else ""
        if (existed && before == content) return "'${resolved.rel}': без изменений (содержимое совпадает)"

        io.write(resolved.absolute, content)
        val verb = if (existed) "Обновлён" else "Создан"
        return "$verb ${resolved.rel} ${changeSummary(before, content)}\n" +
            unifiedDiff(resolved.rel, before, content)
    }

    /**
     * Replace [old] with [new] inside [path].
     *
     * The precise tool: it edits a document without restating it, and — more importantly —
     * it *fails loudly* when [old] isn't there. That turns a hallucinated quote into a
     * visible error instead of a silent wrong edit, which is the one guarantee a prompt
     * can't provide.
     *
     * An ambiguous [old] is refused rather than resolved to the first hit: silently
     * picking one of several matches is how an edit lands in the wrong place.
     */
    fun replace(path: String, old: String, new: String, replaceAll: Boolean = false): String {
        if (old.isEmpty()) return "projectfs error: 'old' не может быть пустым"
        val resolved = when (val candidate = paths.resolveWrite(path)) {
            is ProjectPaths.Resolved.Denied -> return "projectfs error: ${candidate.reason}"
            is ProjectPaths.Resolved.Ok -> candidate
        }
        val before = io.read(resolved.absolute)
            ?: return "projectfs error: '${resolved.rel}' не найден или нечитаем"

        val needle = old.stripLineNumbers()
        val occurrences = before.countOccurrences(needle)
        when {
            occurrences == 0 -> return notFoundHint(resolved.rel, before, needle)
            occurrences > 1 && !replaceAll -> return "projectfs error: 'old' встречается $occurrences раз(а) " +
                "в '${resolved.rel}'. Добавь в него соседние строки, чтобы он стал уникальным, " +
                "или передай replaceAll=true."
        }

        val after = if (replaceAll) before.replace(needle, new) else before.replaceFirst(needle, new)
        if (after == before) return "'${resolved.rel}': без изменений (old и new совпадают)"

        io.write(resolved.absolute, after)
        val where = if (replaceAll) "$occurrences мест(а)" else "1 место"
        return "Обновлён ${resolved.rel} ${changeSummary(before, after)}, заменено $where\n" +
            unifiedDiff(resolved.rel, before, after)
    }

    /** `(+N −M)` counted off the same walk the diff renders. */
    private fun changeSummary(before: String, after: String): String {
        val diff = diffLines(before.toDisplayLines(), after.toDisplayLines())
        return "(+${diff.count { it.op == Op.ADD }} −${diff.count { it.op == Op.DEL }})"
    }
}

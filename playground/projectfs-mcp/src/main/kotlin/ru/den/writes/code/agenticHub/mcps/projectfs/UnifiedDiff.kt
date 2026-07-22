package ru.den.writes.code.agenticHub.mcps.projectfs

/** Context lines shown around each change. */
internal const val DIFF_CONTEXT = 3

/** Body lines a rendered diff may carry before it is clipped. */
internal const val DIFF_MAX_LINES = 120

internal enum class Op { KEEP, DEL, ADD }

internal data class DiffLine(val op: Op, val text: String)

/**
 * Line-level diff of [before] against [after].
 *
 * Common prefix and suffix are stripped before the dynamic-programming step. That is not
 * a micro-optimisation: the calls that matter here are single-line replacements in a long
 * document, where stripping collapses the problem to a couple of lines and the O(N·M)
 * table never materialises.
 */
internal fun diffLines(before: List<String>, after: List<String>): List<DiffLine> {
    var start = 0
    while (start < before.size && start < after.size && before[start] == after[start]) start++

    var endBefore = before.size
    var endAfter = after.size
    while (endBefore > start && endAfter > start && before[endBefore - 1] == after[endAfter - 1]) {
        endBefore--
        endAfter--
    }

    return buildList {
        before.subList(0, start).forEach { add(DiffLine(Op.KEEP, it)) }
        addAll(lcsDiff(before.subList(start, endBefore), after.subList(start, endAfter)))
        before.subList(endBefore, before.size).forEach { add(DiffLine(Op.KEEP, it)) }
    }
}

/** Classic longest-common-subsequence walk over the part that actually differs. */
private fun lcsDiff(a: List<String>, b: List<String>): List<DiffLine> {
    if (a.isEmpty()) return b.map { DiffLine(Op.ADD, it) }
    if (b.isEmpty()) return a.map { DiffLine(Op.DEL, it) }

    val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) {
        for (j in b.indices.reversed()) {
            lengths[i][j] = if (a[i] == b[j]) {
                lengths[i + 1][j + 1] + 1
            } else {
                maxOf(lengths[i + 1][j], lengths[i][j + 1])
            }
        }
    }

    return buildList {
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> add(DiffLine(Op.KEEP, a[i])).also { i++; j++ }
                lengths[i + 1][j] >= lengths[i][j + 1] -> add(DiffLine(Op.DEL, a[i])).also { i++ }
                else -> add(DiffLine(Op.ADD, b[j])).also { j++ }
            }
        }
        while (i < a.size) add(DiffLine(Op.DEL, a[i])).also { i++ }
        while (j < b.size) add(DiffLine(Op.ADD, b[j])).also { j++ }
    }
}

/**
 * A unified diff of [before] → [after] for [path], with [context] lines around each
 * change and the body clipped at [maxLines].
 *
 * Written here rather than shelled out to `diff -u`: the two sides are strings already in
 * memory, and handing them to a subprocess would mean writing the "after" to a temp file
 * — a failure mode and a stray file for no benefit. Being a pure function, it is also the
 * part that is cheapest to test exhaustively.
 */
fun unifiedDiff(
    path: String,
    before: String,
    after: String,
    context: Int = DIFF_CONTEXT,
    maxLines: Int = DIFF_MAX_LINES,
): String {
    if (before == after) return "(без изменений)"

    val beforeLines = before.toDisplayLines()
    val afterLines = after.toDisplayLines()
    val diff = diffLines(beforeLines, afterLines)

    if (diff.none { it.op != Op.KEEP }) {
        // Same lines, different bytes — only the trailing newline moved.
        return "(изменился только перевод строки в конце файла)"
    }

    val annotated = annotate(diff)
    val body = hunkRanges(annotated, context).flatMap { range -> renderHunk(annotated, range) }
    val clipped = body.take(maxLines)

    return buildString {
        appendLine(if (before.isEmpty()) "--- /dev/null" else "--- a/$path")
        appendLine("+++ b/$path")
        clipped.forEach { appendLine(it) }
        if (body.size > clipped.size) {
            append("… (diff обрезан: ${clipped.size} из ${body.size} строк)")
        }
    }.trimEnd('\n')
}

/** A diff line together with its line number on each side (0 when it exists on neither). */
private data class Numbered(val line: DiffLine, val beforeNo: Int, val afterNo: Int)

private fun annotate(diff: List<DiffLine>): List<Numbered> {
    var beforeNo = 0
    var afterNo = 0
    return diff.map { line ->
        when (line.op) {
            Op.KEEP -> Numbered(line, ++beforeNo, ++afterNo)
            Op.DEL -> Numbered(line, ++beforeNo, afterNo)
            Op.ADD -> Numbered(line, beforeNo, ++afterNo)
        }
    }
}

/** Index ranges covering each change plus its context, with overlapping ranges merged. */
private fun hunkRanges(lines: List<Numbered>, context: Int): List<IntRange> {
    val changed = lines.indices.filter { lines[it].line.op != Op.KEEP }
    if (changed.isEmpty()) return emptyList()

    val merged = mutableListOf<IntRange>()
    changed.forEach { index ->
        val from = (index - context).coerceAtLeast(0)
        val to = (index + context).coerceAtMost(lines.lastIndex)
        val last = merged.lastOrNull()
        if (last != null && from <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, to)
        } else {
            merged += from..to
        }
    }
    return merged
}

private fun renderHunk(lines: List<Numbered>, range: IntRange): List<String> {
    val slice = lines.slice(range)
    val beforeCount = slice.count { it.line.op != Op.ADD }
    val afterCount = slice.count { it.line.op != Op.DEL }
    val beforeStart = slice.firstOrNull { it.line.op != Op.ADD }?.beforeNo ?: 0
    val afterStart = slice.firstOrNull { it.line.op != Op.DEL }?.afterNo ?: 0

    return buildList {
        add("@@ -$beforeStart,$beforeCount +$afterStart,$afterCount @@")
        slice.forEach { numbered ->
            val marker = when (numbered.line.op) {
                Op.KEEP -> " "
                Op.DEL -> "-"
                Op.ADD -> "+"
            }
            add(marker + numbered.line.text)
        }
    }
}

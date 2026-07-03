package ru.den.writes.code.agenticHub.scheduling

/**
 * A human-readable roll-up of the results collected so far: how many, the time span
 * they cover (first..last `producedAt`, ms), and the most recent text. Empty input →
 * a "nothing yet" line. Pure and order-independent ("latest" is the max `producedAt`,
 * not the last list element) — integrations can format it further (e.g. nicer times).
 */
fun summarize(results: List<TaskResult>): String {
    if (results.isEmpty()) return "No results yet."
    val first = results.minByOrNull { it.producedAt }!!
    val last = results.maxByOrNull { it.producedAt }!!
    val noun = if (results.size == 1) "result" else "results"
    return "${results.size} $noun from ${first.producedAt} to ${last.producedAt}; latest: ${last.text}"
}

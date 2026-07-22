package ru.den.writes.code.agenticHub.mcps.ticktick

/**
 * Outcome of a planned task at week's end, decided from its current state:
 * - [DONE] — still retrievable and completed (`status == 2`);
 * - [OPEN] — still retrievable and not completed;
 * - [GONE] — no longer returned by the API (404). Most likely completed-and-archived, but could be
 *   deleted — the official API can't tell them apart, so this is "likely done", not proven done.
 */
internal enum class Outcome { DONE, OPEN, GONE }

/** Classifies a planned task from its refetched state: null → [Outcome.GONE], `status==2` → [Outcome.DONE], else [Outcome.OPEN]. */
internal fun classifyOutcome(task: TaskDto?): Outcome = when {
    task == null -> Outcome.GONE
    task.status == 2 -> Outcome.DONE
    else -> Outcome.OPEN
}

/**
 * Renders the plan-vs-actual review: a header with the counts, then a section per outcome listing
 * `- title (id)`. Empty sections are omitted; an empty plan yields a short notice.
 */
internal fun buildWeekReview(snapshot: WeekSnapshot, outcomes: List<Pair<PlannedTask, Outcome>>): String {
    if (outcomes.isEmpty()) return "Week '${snapshot.label}': the snapshot had no planned tasks."
    val done = outcomes.filter { it.second == Outcome.DONE }.map { it.first }
    val open = outcomes.filter { it.second == Outcome.OPEN }.map { it.first }
    val gone = outcomes.filter { it.second == Outcome.GONE }.map { it.first }
    return buildString {
        appendLine(
            "Week '${snapshot.label}' review: ${outcomes.size} planned — " +
                "${done.size} done, ${open.size} not done, ${gone.size} gone.",
        )
        appendTaskSection("Done", done)
        appendTaskSection("Not done", open)
        appendTaskSection("Gone (likely done)", gone)
    }.trimEnd()
}

private fun StringBuilder.appendTaskSection(title: String, tasks: List<PlannedTask>) {
    if (tasks.isEmpty()) return
    appendLine()
    appendLine("$title:")
    tasks.forEach { appendLine("- ${it.title} (${it.id})") }
}

package ru.den.writes.code.agenticHub.mcps.ticktick

/** One activity in the week's plan: total planned [minutes] across [count] scheduled time blocks. */
internal data class PlanItem(val title: String, val minutes: Long, val count: Int)

/**
 * Aggregates scheduled tasks into planned minutes per title — the "plan" half of plan-vs-fact,
 * meant to line up against aTimeLogger's actual time-by-activity. A task counts for the week when
 * its start instant (or, absent a start, its due instant) falls in the half-open window
 * `[fromMs, toMs)`; its planned minutes are `dueDate − startDate` (0 when a bound is missing or
 * non-positive, e.g. an all-day marker). Sorted by planned minutes descending.
 */
internal fun buildWeekPlan(tasks: List<TaskDto>, fromMs: Long, toMs: Long): List<PlanItem> {
    val byTitle = LinkedHashMap<String, PlanItem>()
    for (task in tasks) {
        val at = parseTicktickInstantMillis(task.startDate ?: task.dueDate) ?: continue
        if (at !in fromMs until toMs) continue
        val prev = byTitle[task.title]
        byTitle[task.title] = PlanItem(
            title = task.title,
            minutes = (prev?.minutes ?: 0L) + plannedMinutes(task.startDate, task.dueDate),
            count = (prev?.count ?: 0) + 1,
        )
    }
    return byTitle.values.sortedByDescending { it.minutes }
}

/** Planned minutes for a block: `dueDate − startDate` when both parse and due > start, else 0. */
internal fun plannedMinutes(startDate: String?, dueDate: String?): Long {
    val start = parseTicktickInstantMillis(startDate) ?: return 0
    val due = parseTicktickInstantMillis(dueDate) ?: return 0
    val ms = due - start
    return if (ms > 0) ms / 60_000 else 0
}

/** Minutes as `Xh Ym` (or `Ym` under an hour). */
internal fun formatPlanMinutes(minutes: Long): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}

/** Renders the plan as `title — Xh Ym (×count)` lines, minutes descending, plus a total; notice when empty. */
internal fun formatWeekPlan(items: List<PlanItem>): String {
    if (items.isEmpty()) return "(no scheduled tasks in range)"
    val lines = items.joinToString("\n") { "${it.title} — ${formatPlanMinutes(it.minutes)} (×${it.count})" }
    return "$lines\nTotal planned — ${formatPlanMinutes(items.sumOf { it.minutes })}"
}

package ru.den.writes.code.agenticHub.mcps.ticktick

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Surface over [TicktickApi] + [store] backing the MCP tools. Returns human-readable text —
 * **facts only**, no model call; the assistant does the reasoning. Kept free of I/O (data comes
 * through the [api] port, snapshots through the [store] port) so its logic is unit-tested against
 * fakes.
 */
internal class TicktickReports(
    private val api: TicktickApi,
    private val store: SnapshotStore,
) {

    /** Every project, `id  name` per line; a clear notice when there are none. */
    suspend fun listProjects(): String {
        val projects = api.projects()
        if (projects.isEmpty()) return "(no projects)"
        return projects.joinToString("\n") { formatProject(it) }
    }

    /**
     * The week's plan as planned hours per activity: every scheduled (timed) task across all
     * projects whose start falls in `[fromMs, toMs)`, its `dueDate − startDate` summed by title.
     * The "plan" half of plan-vs-fact — line it up against aTimeLogger's actual time-by-activity.
     */
    suspend fun weekPlan(fromMs: Long, toMs: Long): String {
        val tasks = mutableListOf<TaskDto>()
        for (project in api.projects()) {
            tasks += api.projectData(project.id).tasks
        }
        return formatWeekPlan(buildWeekPlan(tasks, fromMs, toMs))
    }

    /**
     * Snapshots the week's plan under [label]: every undone task across all projects whose dueDate
     * falls in the half-open window `[fromMs, toMs)`. The official API can't list completed tasks,
     * so this captured id set is what [reviewWeek] later checks off to detect what got done.
     */
    suspend fun snapshotWeek(fromMs: Long, toMs: Long, label: String): String {
        val planned = mutableListOf<PlannedTask>()
        for (project in api.projects()) {
            for (task in api.projectData(project.id).tasks) {
                if (isPlannedInRange(task.dueDate, fromMs, toMs)) {
                    planned += PlannedTask(
                        id = task.id,
                        projectId = project.id,
                        title = task.title,
                        dueDate = task.dueDate,
                    )
                }
            }
        }
        val snapshot = WeekSnapshot(label = label, from = fromMs, to = toMs, planned = planned)
        store.write(snapshot)
        return formatSnapshot(snapshot)
    }

    /**
     * Reviews the plan snapshotted under [label]: refetches each planned task by id and classifies
     * it done / not done / gone (see [classifyOutcome]), then renders the plan-vs-actual report.
     * Run at the END of the week. A missing snapshot yields a hint to run [snapshotWeek] first.
     */
    suspend fun reviewWeek(label: String): String {
        val snapshot = store.read(label)
            ?: return "No snapshot '$label' — run snapshot_week for this week first."
        val outcomes = mutableListOf<Pair<PlannedTask, Outcome>>()
        for (planned in snapshot.planned) {
            outcomes += planned to classifyOutcome(api.task(planned.projectId, planned.id))
        }
        return buildWeekReview(snapshot, outcomes)
    }
}

/** One project as `id  name`. The id is what task tools address a project by. */
internal fun formatProject(project: ProjectDto): String = "${project.id}  ${project.name}"

/** Human summary of a saved snapshot: count plus one `- title (id)` line per planned task. */
internal fun formatSnapshot(snapshot: WeekSnapshot): String {
    if (snapshot.planned.isEmpty()) {
        return "Snapshot '${snapshot.label}': no planned tasks with a due date in range."
    }
    val lines = snapshot.planned.joinToString("\n") { "- ${it.title} (${it.id})" }
    return "Snapshot '${snapshot.label}' saved: ${snapshot.planned.size} planned task(s).\n$lines"
}

/** True when [dueDate] parses and falls in the half-open window `[fromMs, toMs)`. Null/blank → false. */
internal fun isPlannedInRange(dueDate: String?, fromMs: Long, toMs: Long): Boolean {
    val due = parseTicktickInstantMillis(dueDate) ?: return false
    return due in fromMs until toMs
}

/**
 * Parses a TickTick date to epoch millis. TickTick emits `2026-07-15T09:00:00.000+0000` (offset
 * without a colon), which `Instant.parse` rejects — so we try that pattern, then the ISO offset
 * form (`+00:00`/`Z`), then a bare `Instant`. Null on blank or anything unparseable.
 */
internal fun parseTicktickInstantMillis(raw: String?): Long? {
    val s = raw?.takeIf { it.isNotBlank() } ?: return null
    for (formatter in TICKTICK_DATE_FORMATS) {
        runCatching { OffsetDateTime.parse(s, formatter).toInstant().toEpochMilli() }
            .getOrNull()?.let { return it }
    }
    return runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
}

/** Midnight of ISO date [date] (`YYYY-MM-DD`) in [zone], as epoch millis. */
internal fun localDateToEpochMillis(date: String, zone: ZoneId): Long =
    LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

private val TICKTICK_DATE_FORMATS = listOf(
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
    DateTimeFormatter.ISO_OFFSET_DATE_TIME,
)

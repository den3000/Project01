package ru.den.writes.code.agenticHub.mcps.atimelogger

import java.time.LocalDate
import java.time.ZoneId

/**
 * Surface over [AtimeloggerApi] backing the MCP tools. Returns human-readable text — **facts
 * only**, no model call; the assistant does the reasoning. Kept free of I/O (all data comes
 * through the [api] port) so its formatting/aggregation is unit-tested against a fake.
 */
internal class AtimeloggerReports(private val api: AtimeloggerApi) {

    /** Every activity type, one per line (name, and color when set); a notice when there are none. */
    suspend fun listActivityTypes(): String {
        val types = api.types()
        if (types.isEmpty()) return "(no activity types)"
        return types.joinToString("\n") { formatActivityType(it) }
    }

    /**
     * Total time tracked per activity type in the half-open window `[fromSec, toSec)`, one line
     * per type sorted by time descending plus a total. Intervals are clipped to the window, so a
     * span straddling a boundary contributes only its in-window part. Names resolve from `/types`.
     */
    suspend fun timeByActivity(fromSec: Long, toSec: Long): String {
        val intervals = api.intervals(fromSec, toSec)
        val nameByGuid = api.types().associate { it.guid to it.name }
        val byGuid = aggregateByActivity(intervals, fromSec, toSec)
        return formatTimeByActivity(byGuid, nameByGuid)
    }
}

/** One activity type as `name` (or `name (#color)` when a color is set). */
internal fun formatActivityType(type: ActivityTypeDto): String =
    if (type.color != null) "${type.name} (${type.color})" else type.name

/**
 * Sums tracked seconds per activity-type guid over [intervals], clipping each interval to the
 * half-open window `[windowFromSec, windowToSec)`. Intervals fully outside the window (or of
 * non-positive clipped length) are dropped; the returned map holds only positive totals.
 */
internal fun aggregateByActivity(
    intervals: List<IntervalDto>,
    windowFromSec: Long,
    windowToSec: Long,
): Map<String, Long> {
    val totals = LinkedHashMap<String, Long>()
    for (interval in intervals) {
        val start = maxOf(interval.from, windowFromSec)
        val end = minOf(interval.to, windowToSec)
        val seconds = end - start
        if (seconds <= 0) continue
        val guid = interval.type?.guid.orEmpty()
        totals[guid] = (totals[guid] ?: 0L) + seconds
    }
    return totals
}

/** Seconds as `Xh Ym` (or `Ym` under an hour); minute-truncated. E.g. 3661 → `1h 1m`, 90 → `1m`. */
internal fun formatDuration(seconds: Long): String {
    val totalMinutes = seconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/**
 * Renders per-guid [byGuid] totals as `name — duration` lines, time descending, then a `Total`
 * line. A guid with no entry in [nameByGuid] shows the guid itself (blank → `(unknown)`). Empty
 * input → a clear notice.
 */
internal fun formatTimeByActivity(byGuid: Map<String, Long>, nameByGuid: Map<String, String>): String {
    if (byGuid.isEmpty()) return "(no tracked time in range)"
    val lines = byGuid.entries
        .sortedByDescending { it.value }
        .joinToString("\n") { (guid, seconds) ->
            val name = nameByGuid[guid] ?: guid.ifBlank { "(unknown)" }
            "$name — ${formatDuration(seconds)}"
        }
    return "$lines\nTotal — ${formatDuration(byGuid.values.sum())}"
}

/** Midnight of ISO date [date] (`YYYY-MM-DD`) in [zone], as unix seconds. */
internal fun localDateToEpochSeconds(date: String, zone: ZoneId): Long =
    LocalDate.parse(date).atStartOfDay(zone).toEpochSecond()

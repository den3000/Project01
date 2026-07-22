package ru.den.writes.code.agenticHub.mcps.atimelogger

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
}

/** One activity type as `name` (or `name (#color)` when a color is set). */
internal fun formatActivityType(type: ActivityTypeDto): String =
    if (type.color != null) "${type.name} (${type.color})" else type.name

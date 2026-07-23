package ru.den.writes.code.agenticHub.mcps.atimelogger

/**
 * I/O port over the aTimeLogger REST API — the impure edge of the server. Kept as a small
 * interface so [AtimeloggerReports] (the logic) is unit-tested against an in-memory fake,
 * with no network. Production impl is [HttpAtimeloggerApi]. Methods grow as tools are added.
 */
internal interface AtimeloggerApi {
    /** All activity types defined for the account (`GET /types`). */
    suspend fun types(): List<ActivityTypeDto>

    /** Tracked intervals overlapping the half-open window `[fromSec, toSec)`, unix seconds (`GET /intervals`). */
    suspend fun intervals(fromSec: Long, toSec: Long): List<IntervalDto>
}

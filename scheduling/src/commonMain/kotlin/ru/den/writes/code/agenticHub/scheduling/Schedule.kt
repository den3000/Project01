package ru.den.writes.code.agenticHub.scheduling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * When a task should fire. Sealed so new kinds (e.g. cron) can be added as another
 * variant without touching the existing ones. All times are epoch milliseconds; the
 * core never reads the clock itself — every computation takes `now`/`anchor` as a
 * parameter, which is what makes the math pure and offline-testable.
 */
@Serializable
sealed interface Schedule {
    /** One-shot reminder: fire once, [delayMs] after the anchor moment. */
    @Serializable
    @SerialName("after")
    data class After(val delayMs: Long) : Schedule

    /** Periodic: fire every [intervalMs], starting one interval after the anchor. */
    @Serializable
    @SerialName("every")
    data class Every(val intervalMs: Long) : Schedule
}

/** The next firing moment for a schedule whose countdown starts at [anchorAt] (ms). */
fun Schedule.nextRunAt(anchorAt: Long): Long = when (this) {
    is Schedule.After -> anchorAt + delayMs
    is Schedule.Every -> anchorAt + intervalMs
}

/** Whether the task is ready to fire at [now]: active and its moment has arrived. */
fun ScheduledTask.isDue(now: Long): Boolean =
    status == TaskStatus.ACTIVE && now >= nextRunAt

/**
 * The task's state after a successful firing at [now]:
 * - [Schedule.After] → one-shot, moves to [TaskStatus.DONE];
 * - [Schedule.Every] → reschedules to the next occurrence strictly after [now]. If the
 *   ticker overslept several intervals, missed occurrences are skipped (one firing
 *   closes the whole gap — no burst of catch-up runs).
 */
fun ScheduledTask.advance(now: Long): ScheduledTask = when (val s = schedule) {
    is Schedule.After -> copy(status = TaskStatus.DONE)
    is Schedule.Every -> copy(nextRunAt = nextEvery(nextRunAt, s.intervalMs, now))
}

/** Smallest `currentNextRunAt + k*interval` (k ≥ 1) that is strictly greater than [now]. */
private fun nextEvery(currentNextRunAt: Long, intervalMs: Long, now: Long): Long {
    if (intervalMs <= 0L) return now + 1L // degenerate interval — just step forward
    val elapsed = now - currentNextRunAt
    val periods = if (elapsed < 0L) 1L else elapsed / intervalMs + 1L
    return currentNextRunAt + periods * intervalMs
}

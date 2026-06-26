package ru.den.writes.code.project01.cliJvm.command

/**
 * A scheduled task parsed from `-schedule` / `/schedule`: what to run and how often. The
 * runtime ([CommandExecutor]) turns [seconds]/[periodic] into a scheduling Schedule and
 * routes by variant — collect calls an MCP tool, agent injects a turn.
 */
internal sealed interface ScheduleSpec {
    /** Seconds until the first (and, when [periodic], each subsequent) firing. */
    val seconds: Int

    /** True = periodic (`every`); false = one-shot (`after`). */
    val periodic: Boolean

    /** Call an MCP [tool] (optional JSON [args]) on the schedule and store its text. */
    data class Collect(
        val tool: String,
        val args: String?,
        override val seconds: Int,
        override val periodic: Boolean,
    ) : ScheduleSpec

    /** Inject [prompt] as a turn on the schedule. */
    data class Agent(
        val prompt: String,
        override val seconds: Int,
        override val periodic: Boolean,
    ) : ScheduleSpec
}

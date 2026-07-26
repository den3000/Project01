package ru.den.writes.code.agenticHub.features.fsm

/**
 * One budget: [attempt] spent out of [max]. Value type, so spending it is a copy
 * and a task that was never retried is `attempt == 0` everywhere.
 */
data class RetryState(
    val attempt: Int,
    val max: Int,
) {
    val exhausted: Boolean get() = attempt >= max

    /** One attempt lighter, or null when there was none left to take. */
    fun spend(): RetryState? = if (exhausted) null else copy(attempt = attempt + 1)

    companion object {
        /**
         * Restarts of the whole task. Five is generous on purpose: the measured
         * run never needed more than three, and the tail is cheap because most
         * tasks never restart at all.
         */
        const val TASK_MAX: Int = 5

        /**
         * Turns one stage may cost before the task is restarted — whether the
         * turn left the stage where it was or was sent back to be rewritten. Ten
         * because a stage doing real work takes several turns; ten of them
         * without moving on is not work, it is a loop.
         */
        const val STAGE_MAX: Int = 10

        /**
         * Unreachable-provider failures the whole task may absorb before the run
         * is abandoned. Spans the task and is never refilled — this budget
         * measures an outage, which does not care which stage the task is on or
         * how many times it has started over. Lower than the rest on purpose: an
         * outage is an external fact, not something the task can be talked out
         * of, and fifteen pokes at a wall merely cost more than five.
         */
        const val TRANSPORT_MAX: Int = 5

        fun task() = RetryState(0, TASK_MAX)

        fun stage() = RetryState(0, STAGE_MAX)

        fun transport() = RetryState(0, TRANSPORT_MAX)
    }
}

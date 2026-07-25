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
        fun task() = RetryState(0, 5)

        /** Turns one stage may sit still before the task is restarted. */
        fun stage() = RetryState(0, 10)

        /**
         * Rewrites one stage may spend inside its turns before the task is
         * restarted. Three fully-blocked turns' worth (the engine caps one turn
         * at five): a rewrite here and there is a judge doing its job, fifteen
         * without leaving the stage is an attempt arguing with its own auditor.
         */
        fun turn() = RetryState(0, 15)

        /**
         * Unreachable-provider failures the whole task may absorb before the run
         * is abandoned. Same fifteen, but spanning the task and never refilled:
         * this budget measures an outage, and an outage does not care which stage
         * the task is on or how many times it has started over.
         */
        fun transport() = RetryState(0, 15)
    }
}

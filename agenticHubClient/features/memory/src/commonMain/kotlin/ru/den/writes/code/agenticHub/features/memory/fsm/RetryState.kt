package ru.den.writes.code.agenticHub.features.memory.fsm

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
    }
}

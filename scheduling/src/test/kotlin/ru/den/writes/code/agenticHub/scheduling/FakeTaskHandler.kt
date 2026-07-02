package ru.den.writes.code.agenticHub.scheduling

/** Test [TaskHandler]: returns a fixed [reply] (or null), optionally throws, records calls. */
internal class FakeTaskHandler(
    private val reply: String? = "ok",
    private val failWith: Throwable? = null,
) : TaskHandler {
    val handled = mutableListOf<ScheduledTask>()
    val callCount: Int get() = handled.size

    override suspend fun handle(task: ScheduledTask): String? {
        handled += task
        failWith?.let { throw it }
        return reply
    }
}

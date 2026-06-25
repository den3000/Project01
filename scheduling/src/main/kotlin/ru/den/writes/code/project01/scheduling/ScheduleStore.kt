package ru.den.writes.code.project01.scheduling

/**
 * Persistence seam for the scheduler's state: the task list plus the results collected
 * so far. Not thread-safe on its own — [SchedulerEngine] serializes all access under its
 * own lock. Integrations may supply their own implementation (e.g. a Room-backed one);
 * the engine depends only on this interface.
 */
interface ScheduleStore {
    fun loadTasks(): List<ScheduledTask>
    fun saveTasks(tasks: List<ScheduledTask>)

    fun loadResults(): List<TaskResult>
    fun appendResult(result: TaskResult)
}

/** In-memory [ScheduleStore] for tests and runtimes that don't need to persist. */
class InMemoryScheduleStore(
    initialTasks: List<ScheduledTask> = emptyList(),
    initialResults: List<TaskResult> = emptyList(),
) : ScheduleStore {
    private val tasks = initialTasks.toMutableList()
    private val results = initialResults.toMutableList()

    override fun loadTasks(): List<ScheduledTask> = tasks.toList()

    override fun saveTasks(tasks: List<ScheduledTask>) {
        this.tasks.clear()
        this.tasks.addAll(tasks)
    }

    override fun loadResults(): List<TaskResult> = results.toList()

    override fun appendResult(result: TaskResult) {
        results.add(result)
    }
}

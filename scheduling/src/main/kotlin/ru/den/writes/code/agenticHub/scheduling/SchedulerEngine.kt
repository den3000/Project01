package ru.den.writes.code.agenticHub.scheduling

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The scheduler. All state lives behind a [Mutex], so control calls (add/cancel/list/
 * summary) and the background ticker (tick/runLoop) can run concurrently from different
 * coroutines. The clock is injected ([now]) so tests run on fake time without sleeping;
 * [handler] is the per-tick payload; [store] is the persistence (any implementation).
 *
 * The engine does NOT create a scope or pick a Dispatcher — [runLoop] runs in whatever
 * context the integration launches it in (openmeteo-mcp uses `Dispatchers.IO`). That is what lets
 * both integrations sit on top purely additively.
 */
class SchedulerEngine(
    private val store: ScheduleStore,
    private val handler: TaskHandler,
    private val now: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()

    /** Add a task: compute its first [ScheduledTask.nextRunAt] from [now], persist, return it. */
    suspend fun add(label: String, schedule: Schedule): ScheduledTask = mutex.withLock {
        val task = ScheduledTask(
            id = newId(),
            label = label,
            schedule = schedule,
            nextRunAt = schedule.nextRunAt(now()),
        )
        store.saveTasks(store.loadTasks() + task)
        task
    }

    /** Cancel by [id]: mark CANCELLED + persist. True iff a still-active task changed. */
    suspend fun cancel(id: String): Boolean = mutex.withLock {
        val tasks = store.loadTasks()
        val target = tasks.firstOrNull { it.id == id }
        if (target == null || target.status != TaskStatus.ACTIVE) {
            false
        } else {
            store.saveTasks(tasks.map { if (it.id == id) it.copy(status = TaskStatus.CANCELLED) else it })
            true
        }
    }

    /** Snapshot of all tasks. */
    suspend fun list(): List<ScheduledTask> = mutex.withLock { store.loadTasks() }

    /**
     * One pass over due tasks. The lock is released while handlers run (they may do slow
     * I/O), then re-taken to commit: store each non-null result and advance the schedule
     * (Every reschedules, After → DONE). A handler that throws is swallowed so it neither
     * aborts the tick nor blocks the others, and the schedule still advances — a failing
     * task can't wedge the ticker by staying perpetually due. A task cancelled during the
     * I/O window is not advanced. Returns how many tasks fired.
     */
    suspend fun tick(): Int {
        val nowMs = now()
        val due = mutex.withLock { store.loadTasks().filter { it.isDue(nowMs) } }
        if (due.isEmpty()) return 0

        val results = mutableListOf<TaskResult>()
        for (task in due) {
            val text = runCatching { handler.handle(task) }.getOrNull()
            if (text != null) results += TaskResult(taskId = task.id, producedAt = nowMs, text = text)
        }

        val dueIds = due.mapTo(mutableSetOf()) { it.id }
        mutex.withLock {
            results.forEach(store::appendResult)
            store.saveTasks(
                store.loadTasks().map { t ->
                    if (t.id in dueIds && t.status == TaskStatus.ACTIVE) t.advance(nowMs) else t
                },
            )
        }
        return due.size
    }

    /** Thin loop: [tick] then wait [tickMs], until the surrounding coroutine is cancelled. */
    suspend fun runLoop(tickMs: Long) {
        while (currentCoroutineContext().isActive) {
            tick()
            delay(tickMs)
        }
    }

    /** Human-readable aggregate of the stored results. */
    suspend fun summary(): String = mutex.withLock { summarize(store.loadResults()) }
}

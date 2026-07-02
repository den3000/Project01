package ru.den.writes.code.agenticHub.scheduling

import kotlin.test.Test
import kotlin.test.assertEquals

class InMemoryScheduleStoreTest {

    @Test
    fun `when tasks saved - then loadTasks returns them`() {
        // given
        val store = InMemoryScheduleStore()
        val tasks = listOf(ScheduledTask("a", "x", Schedule.Every(1_000), nextRunAt = 1_000))

        // when
        store.saveTasks(tasks)

        // then
        assertEquals(tasks, store.loadTasks())
    }

    @Test
    fun `when results appended - then loadResults returns them in order`() {
        // given
        val store = InMemoryScheduleStore()

        // when
        store.appendResult(TaskResult("a", producedAt = 1, text = "one"))
        store.appendResult(TaskResult("a", producedAt = 2, text = "two"))

        // then
        assertEquals(
            listOf(TaskResult("a", 1, "one"), TaskResult("a", 2, "two")),
            store.loadResults(),
        )
    }

    @Test
    fun `when initial state provided - then it is returned`() {
        // given
        val store = InMemoryScheduleStore(
            initialTasks = listOf(ScheduledTask("a", "x", Schedule.After(5), nextRunAt = 5)),
            initialResults = listOf(TaskResult("a", producedAt = 1, text = "seed")),
        )

        // when - then
        assertEquals(1, store.loadTasks().size)
        assertEquals(listOf(TaskResult("a", 1, "seed")), store.loadResults())
    }
}

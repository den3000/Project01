package ru.den.writes.code.agenticHub.scheduling

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchedulerEngineTest {

    @Test
    fun `when task is due - then handler runs and result is stored`() = runTest {
        // given
        var clock = 0L
        val store = InMemoryScheduleStore()
        val handler = FakeTaskHandler(reply = "Moscow: 12C")
        val engine = SchedulerEngine(store, handler, now = { clock }, newId = { "t1" })
        engine.add("Moscow", Schedule.Every(1_000))

        // when - clock reaches the due moment
        clock = 1_000
        val fired = engine.tick()

        // then
        assertEquals(1, fired)
        assertEquals(1, handler.callCount)
        assertEquals(
            listOf(TaskResult(taskId = "t1", producedAt = 1_000, text = "Moscow: 12C")),
            store.loadResults(),
        )
    }

    @Test
    fun `when task not due - then handler is skipped`() = runTest {
        // given
        var clock = 0L
        val store = InMemoryScheduleStore()
        val handler = FakeTaskHandler()
        val engine = SchedulerEngine(store, handler, now = { clock }, newId = { "t1" })
        engine.add("x", Schedule.Every(1_000)) // nextRunAt = 1000

        // when - still before due
        clock = 500
        val fired = engine.tick()

        // then
        assertEquals(0, fired)
        assertEquals(0, handler.callCount)
        assertEquals(emptyList(), store.loadResults())
    }

    @Test
    fun `when one-shot fires - then it becomes DONE and never fires again`() = runTest {
        // given
        var clock = 0L
        val store = InMemoryScheduleStore()
        val handler = FakeTaskHandler(reply = "ping")
        val engine = SchedulerEngine(store, handler, now = { clock }, newId = { "t1" })
        engine.add("remind", Schedule.After(1_000))

        // when - fire, then advance well past and tick again
        clock = 1_000
        engine.tick()
        clock = 5_000
        val firedAgain = engine.tick()

        // then
        assertEquals(TaskStatus.DONE, store.loadTasks().single().status)
        assertEquals(0, firedAgain)
        assertEquals(1, handler.callCount)
    }

    @Test
    fun `when Every task fires - then it stays ACTIVE and reschedules`() = runTest {
        // given
        var clock = 0L
        val store = InMemoryScheduleStore()
        val engine = SchedulerEngine(store, FakeTaskHandler(), now = { clock }, newId = { "t1" })
        engine.add("x", Schedule.Every(1_000)) // nextRunAt = 1000

        // when
        clock = 1_000
        engine.tick()

        // then
        val task = store.loadTasks().single()
        assertEquals(TaskStatus.ACTIVE, task.status)
        assertEquals(2_000, task.nextRunAt)
    }

    @Test
    fun `when cancel called - then task is CANCELLED and skipped on next tick`() = runTest {
        // given
        var clock = 0L
        val store = InMemoryScheduleStore()
        val handler = FakeTaskHandler()
        val engine = SchedulerEngine(store, handler, now = { clock }, newId = { "t1" })
        val task = engine.add("x", Schedule.Every(1_000))

        // when
        val cancelled = engine.cancel(task.id)
        clock = 1_000
        val fired = engine.tick()

        // then
        assertTrue(cancelled)
        assertEquals(TaskStatus.CANCELLED, store.loadTasks().single().status)
        assertEquals(0, fired)
        assertEquals(0, handler.callCount)
    }

    @Test
    fun `when cancel unknown id - then returns false`() = runTest {
        // given
        val engine = SchedulerEngine(InMemoryScheduleStore(), FakeTaskHandler(), now = { 0L })

        // when - then
        assertFalse(engine.cancel("nope"))
    }

    @Test
    fun `when handler returns null - then nothing is stored but schedule advances`() = runTest {
        // given - async-style handler: fired, but no result to store right now
        var clock = 0L
        val store = InMemoryScheduleStore()
        val handler = FakeTaskHandler(reply = null)
        val engine = SchedulerEngine(store, handler, now = { clock }, newId = { "t1" })
        engine.add("x", Schedule.Every(1_000))

        // when
        clock = 1_000
        val fired = engine.tick()

        // then
        assertEquals(1, fired)
        assertEquals(1, handler.callCount)
        assertEquals(emptyList(), store.loadResults())
        assertEquals(2_000, store.loadTasks().single().nextRunAt)
    }

    @Test
    fun `when handler throws - then tick survives and other tasks still run`() = runTest {
        // given - one task's handler blows up, the other returns normally
        var clock = 0L
        var seq = 0
        val store = InMemoryScheduleStore()
        val handler = TaskHandler { task ->
            if (task.label == "boom") throw RuntimeException("network down")
            "ok:${task.label}"
        }
        val engine = SchedulerEngine(store, handler, now = { clock }, newId = { "id${seq++}" })
        engine.add("boom", Schedule.Every(1_000))
        engine.add("good", Schedule.Every(1_000))

        // when
        clock = 1_000
        val fired = engine.tick()

        // then - tick didn't throw; good produced a result; both rescheduled
        assertEquals(2, fired)
        assertEquals(listOf("ok:good"), store.loadResults().map { it.text })
        assertTrue(store.loadTasks().all { it.nextRunAt == 2_000L })
    }

    @Test
    fun `when add called - then nextRunAt is computed from now`() = runTest {
        // given
        val store = InMemoryScheduleStore()
        val engine = SchedulerEngine(store, FakeTaskHandler(), now = { 5_000L }, newId = { "t1" })

        // when
        val task = engine.add("x", Schedule.Every(1_000))

        // then
        assertEquals(6_000, task.nextRunAt)
        assertEquals(TaskStatus.ACTIVE, task.status)
    }

    @Test
    fun `when engine built over a populated store - then existing tasks tick`() = runTest {
        // given - simulates resume: the store already holds a task from a prior run
        var clock = 0L
        val seeded = ScheduledTask("t1", "Moscow", Schedule.Every(1_000), nextRunAt = 1_000)
        val store = InMemoryScheduleStore(initialTasks = listOf(seeded))
        val handler = FakeTaskHandler(reply = "12C")
        val engine = SchedulerEngine(store, handler, now = { clock }, newId = { "new" })

        // when
        clock = 1_000
        val fired = engine.tick()

        // then
        assertEquals(1, fired)
        assertEquals(1, handler.callCount)
        assertEquals("12C", store.loadResults().single().text)
    }

    @Test
    fun `when summary requested - then it aggregates stored results`() = runTest {
        // given
        val store = InMemoryScheduleStore(
            initialResults = listOf(
                TaskResult("t1", producedAt = 1_000, text = "a"),
                TaskResult("t1", producedAt = 2_000, text = "b"),
            ),
        )
        val engine = SchedulerEngine(store, FakeTaskHandler(), now = { 0L })

        // when - then
        assertEquals("2 results from 1000 to 2000; latest: b", engine.summary())
    }
}

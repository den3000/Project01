package ru.den.writes.code.agenticHub.scheduling

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonFileScheduleStoreTest {

    @Test
    fun `when tasks saved and reloaded - then round-trip equal`() = withTempDir { dir ->
        // given
        val store = JsonFileScheduleStore(File(dir, "scheduler.json"))
        val tasks = listOf(
            ScheduledTask("a", "Moscow", Schedule.Every(60_000), nextRunAt = 60_000),
            ScheduledTask("b", "remind me", Schedule.After(5_000), nextRunAt = 5_000, status = TaskStatus.DONE),
        )

        // when
        store.saveTasks(tasks)

        // then
        assertEquals(tasks, store.loadTasks())
    }

    @Test
    fun `when results appended and reloaded - then round-trip in order`() = withTempDir { dir ->
        // given
        val store = JsonFileScheduleStore(File(dir, "scheduler.json"))

        // when
        store.appendResult(TaskResult("a", producedAt = 1_000, text = "first"))
        store.appendResult(TaskResult("a", producedAt = 2_000, text = "second"))

        // then
        assertEquals(
            listOf(
                TaskResult("a", 1_000, "first"),
                TaskResult("a", 2_000, "second"),
            ),
            store.loadResults(),
        )
    }

    @Test
    fun `when saving tasks - then results are preserved`() = withTempDir { dir ->
        // given - tasks and results share one file
        val store = JsonFileScheduleStore(File(dir, "scheduler.json"))
        store.appendResult(TaskResult("a", producedAt = 1_000, text = "kept"))

        // when
        store.saveTasks(listOf(ScheduledTask("a", "Moscow", Schedule.Every(60_000), nextRunAt = 60_000)))

        // then
        assertEquals(listOf(TaskResult("a", 1_000, "kept")), store.loadResults())
    }

    @Test
    fun `when file absent - then loadTasks returns empty`() = withTempDir { dir ->
        // given
        val store = JsonFileScheduleStore(File(dir, "missing.json"))

        // when - then
        assertEquals(emptyList<ScheduledTask>(), store.loadTasks())
    }

    @Test
    fun `when file absent - then loadResults returns empty`() = withTempDir { dir ->
        // given
        val store = JsonFileScheduleStore(File(dir, "missing.json"))

        // when - then
        assertEquals(emptyList<TaskResult>(), store.loadResults())
    }

    @Test
    fun `when file corrupt - then loadTasks returns empty`() = withTempDir { dir ->
        // given
        val file = File(dir, "scheduler.json").apply { writeText("{ this is not valid json") }
        val store = JsonFileScheduleStore(file)

        // when - then
        assertEquals(emptyList<ScheduledTask>(), store.loadTasks())
    }

    @Test
    fun `when saved twice - then no temp file is left behind`() = withTempDir { dir ->
        // given
        val file = File(dir, "scheduler.json")
        val store = JsonFileScheduleStore(file)

        // when
        store.saveTasks(listOf(ScheduledTask("a", "x", Schedule.After(1), nextRunAt = 1)))
        store.saveTasks(listOf(ScheduledTask("b", "y", Schedule.After(2), nextRunAt = 2)))

        // then - atomic write leaves only the real file, no .tmp sibling
        val leftovers = dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        assertEquals(setOf("scheduler.json"), leftovers)
        assertTrue(file.exists())
    }
}

private inline fun withTempDir(block: (File) -> Unit) {
    val dir = Files.createTempDirectory("project01-scheduling-").toFile()
    try {
        block(dir)
    } finally {
        dir.deleteRecursively()
    }
}

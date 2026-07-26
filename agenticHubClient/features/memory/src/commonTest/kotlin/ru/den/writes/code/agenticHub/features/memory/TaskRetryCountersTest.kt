package ru.den.writes.code.agenticHub.features.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TaskRetryCountersTest {

    @Test
    fun `when a task with spent retries is rendered and parsed - then the counters survive`() {
        // given
        val notes = TaskNotes(
            taskId = "auth-service",
            stage = TaskStage.EXECUTION,
            deepestStage = TaskStage.VALIDATION,
            taskRetriesSpent = 2,
            stageRetriesSpent = 7,
            transportRetriesSpent = 1,
        )

        // when
        val actual = FileMemoryStore.parseTaskNotes(notes.taskId, FileMemoryStore.renderTaskNotes(notes))

        // then
        assertEquals(notes, actual)
    }

    @Test
    fun `when a task has spent nothing - then no counter sections are written`() {
        // given
        val notes = TaskNotes(taskId = "auth-service", stage = TaskStage.EXECUTION)

        // when
        val actual = FileMemoryStore.renderTaskNotes(notes)

        // then
        assertFalse("retries" in actual.lowercase(), actual)
    }

    @Test
    fun `when a task file predates the counters - then they read as unspent`() {
        // given
        val raw = "# Task: auth-service\n\n## Stage\nexecution\n"

        // when
        val actual = FileMemoryStore.parseTaskNotes("auth-service", raw)

        // then
        assertEquals(0, actual.taskRetriesSpent)
        assertEquals(0, actual.stageRetriesSpent)
        assertEquals(0, actual.transportRetriesSpent)
    }

    @Test
    fun `when a counter section holds something other than a count - then it reads as unspent`() {
        // given
        val bodies = listOf("", "many", "-3", "2 attempts")

        // when
        val actuals = bodies.map { body -> body to FileMemoryStore.parseTaskNotes("t", "# Task: t\n\n## Stage retries\n$body\n") }

        // then
        actuals.forEach { (body, actual) ->
            assertEquals(0, actual.stageRetriesSpent, "stageRetriesSpent(\"$body\")")
        }
    }
}

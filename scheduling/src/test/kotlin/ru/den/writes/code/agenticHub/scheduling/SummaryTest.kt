package ru.den.writes.code.agenticHub.scheduling

import kotlin.test.Test
import kotlin.test.assertEquals

class SummaryTest {

    @Test
    fun `when results empty - then summary says no results`() {
        // when - then
        assertEquals("No results yet.", summarize(emptyList()))
    }

    @Test
    fun `when one result - then summary count is one and shows its text`() {
        // given
        val results = listOf(TaskResult(taskId = "t1", producedAt = 1_000, text = "sunny 20C"))

        // when
        val actual = summarize(results)

        // then
        assertEquals("1 result from 1000 to 1000; latest: sunny 20C", actual)
    }

    @Test
    fun `when many results - then summary count and first-last range correct`() {
        // given
        val results = listOf(
            TaskResult("t1", producedAt = 1_000, text = "a"),
            TaskResult("t1", producedAt = 3_000, text = "b"),
            TaskResult("t1", producedAt = 5_000, text = "c"),
        )

        // when
        val actual = summarize(results)

        // then
        assertEquals("3 results from 1000 to 5000; latest: c", actual)
    }

    @Test
    fun `when results out of order - then latest is the max producedAt text`() {
        // given
        val results = listOf(
            TaskResult("t1", producedAt = 5_000, text = "newest"),
            TaskResult("t1", producedAt = 1_000, text = "oldest"),
            TaskResult("t1", producedAt = 3_000, text = "middle"),
        )

        // when
        val actual = summarize(results)

        // then
        assertEquals("3 results from 1000 to 5000; latest: newest", actual)
    }
}

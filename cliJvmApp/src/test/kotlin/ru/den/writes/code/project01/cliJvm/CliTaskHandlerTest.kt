package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.den.writes.code.project01.scheduling.Schedule
import ru.den.writes.code.project01.scheduling.ScheduledTask
import ru.den.writes.code.project01.shared.llm.ToolCall
import ru.den.writes.code.project01.shared.llm.ToolExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Offline tests for [CliTaskHandler] — routes a fired task by id to a tool call or a turn. */
class CliTaskHandlerTest {

    @Test
    fun `when a collect task fires - then it calls the tool and returns its text`() = runTest {
        // given
        var seen: ToolCall? = null
        val executor = ToolExecutor { call -> seen = call; "Paris: rain" }
        val args = JsonObject(mapOf("city" to JsonPrimitive("Paris")))
        val handler = CliTaskHandler(mapOf("t1" to ScheduleAction.Collect("current_weather", args)), executor)

        // when
        val result = handler.handle(task("t1"))

        // then
        assertEquals(ToolCall("current_weather", args), seen)
        assertEquals("Paris: rain", result)
    }

    @Test
    fun `when an agent task fires - then it returns null (no synchronous result)`() = runTest {
        // given
        val handler = CliTaskHandler(mapOf("t2" to ScheduleAction.Agent("daily digest")), toolExecutor = null)

        // when - then
        assertNull(handler.handle(task("t2")))
    }

    @Test
    fun `when the task id is unknown - then null`() = runTest {
        // given
        val handler = CliTaskHandler(emptyMap(), toolExecutor = null)

        // when - then
        assertNull(handler.handle(task("nope")))
    }

    private fun task(id: String): ScheduledTask =
        ScheduledTask(id = id, label = "x", schedule = Schedule.Every(1_000L), nextRunAt = 0L)
}

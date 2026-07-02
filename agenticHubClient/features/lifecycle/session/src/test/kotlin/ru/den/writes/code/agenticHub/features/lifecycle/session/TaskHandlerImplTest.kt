package ru.den.writes.code.agenticHub.features.lifecycle.session

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.den.writes.code.agenticHub.features.lifecycle.session.scheduling.ScheduleAction
import ru.den.writes.code.agenticHub.features.lifecycle.session.scheduling.TaskHandlerImpl
import ru.den.writes.code.agenticHub.scheduling.Schedule
import ru.den.writes.code.agenticHub.scheduling.ScheduledTask
import ru.den.writes.code.agenticHub.features.llm.ToolCall
import ru.den.writes.code.agenticHub.features.llm.ToolExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Offline tests for [TaskHandlerImpl] — routes a fired task by id to a tool call or a turn. */
class TaskHandlerImplTest {

    @Test
    fun `when a collect task fires - then it calls the tool and returns its text`() = runTest {
        // given
        var seen: ToolCall? = null
        val executor = ToolExecutor { call -> seen = call; "Paris: rain" }
        val args = JsonObject(mapOf("city" to JsonPrimitive("Paris")))
        val handler = TaskHandlerImpl(mapOf("t1" to ScheduleAction.Collect("current_weather", args)), executor)

        // when
        val result = handler.handle(task("t1"))

        // then
        assertEquals(ToolCall("current_weather", args), seen)
        assertEquals("Paris: rain", result)
    }

    @Test
    fun `when an agent task fires - then it injects the prompt as a turn and returns null`() = runTest {
        // given
        var submitted: String? = null
        val handler = TaskHandlerImpl(mapOf("t2" to ScheduleAction.Agent("daily digest")), toolExecutor = null)
            .apply { submitTurn = { submitted = it } }

        // when
        val result = handler.handle(task("t2"))

        // then
        assertEquals("daily digest", submitted)
        assertNull(result)
    }

    @Test
    fun `when the task id is unknown - then null`() = runTest {
        // given
        val handler = TaskHandlerImpl(emptyMap(), toolExecutor = null)

        // when - then
        assertNull(handler.handle(task("nope")))
    }

    private fun task(id: String): ScheduledTask =
        ScheduledTask(id = id, label = "x", schedule = Schedule.Every(1_000L), nextRunAt = 0L)
}

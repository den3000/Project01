package ru.den.writes.code.agenticHub.features.agent

import ru.den.writes.code.agenticHub.features.llm.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The session-wide evidence window: order, eviction, and the count of what fell
 * out. The count matters as much as the window — a silently shortened list reads
 * to the judge as a step that was never taken.
 */
class ToolCallLogTest {

    @Test
    fun `when a fresh log is asked - then it holds nothing and dropped nothing`() {
        // given / when
        val log = ToolCallLog()

        // then
        assertTrue(log.calls.isEmpty())
        assertEquals(0, log.dropped)
    }

    @Test
    fun `when several turns record - then the calls keep session order oldest first`() {
        // given
        val log = ToolCallLog()

        // when — two turns, two calls each
        log.record(listOf(executed("find_user"), executed("search_tickets")))
        log.record(listOf(executed("get_ticket")))

        // then
        assertEquals(listOf("find_user", "search_tickets", "get_ticket"), log.calls.map { it.call.name })
        assertEquals(0, log.dropped)
    }

    @Test
    fun `when the window overflows - then the oldest calls give way`() {
        // given — capacity of two
        val log = ToolCallLog(capacity = 2)

        // when
        log.record(listOf(executed("first"), executed("second")))
        log.record(listOf(executed("third")))

        // then — the newest survive, the eldest is gone
        assertEquals(listOf("second", "third"), log.calls.map { it.call.name })
    }

    @Test
    fun `when calls are evicted - then dropped counts every one of them`() {
        // given
        val log = ToolCallLog(capacity = 1)

        // when
        log.record(listOf(executed("a"), executed("b"), executed("c")))

        // then — two lost, and the log says so rather than pretending they never ran
        assertEquals(listOf("c"), log.calls.map { it.call.name })
        assertEquals(2, log.dropped)
    }

    private fun executed(name: String): ExecutedToolCall =
        ExecutedToolCall(ToolCall(name = name, arguments = JsonObject(emptyMap())), output = "ok")
}

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
    fun `when an investigation spans several turns - then the default window loses no call`() {
        // given — work that runs across stages, each turn spending its whole tool budget
        val log = ToolCallLog()

        // when
        repeat(TURNS_OF_AN_INVESTIGATION) { turn ->
            log.record(List(CALLS_PER_BUSY_TURN) { index -> executed("search_$turn$index") })
        }

        // then — the call that established a fact is still evidence when the fact is restated
        assertEquals(0, log.dropped)
        assertEquals("search_00", log.calls.first().call.name)
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

    private companion object {
        /** A turn that spends the whole tool budget — `AgentResponder.MAX_TOOL_ROUNDS`. */
        const val CALLS_PER_BUSY_TURN = 6

        /** Long enough to cross stages: a fact is established early and restated late. */
        const val TURNS_OF_AN_INVESTIGATION = 4
    }
}

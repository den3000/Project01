package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskTransitionsTest {

    private val machine = TaskStateMachine()

    @Test
    fun `when allowedNext queried - then matches the transition table`() {
        // given
        val table = mapOf(
            Stage.CLARIFICATION to setOf(Stage.PLANNING),
            Stage.PLANNING to setOf(Stage.EXECUTION, Stage.CLARIFICATION),
            Stage.EXECUTION to setOf(Stage.VALIDATION, Stage.PLANNING),
            Stage.VALIDATION to setOf(Stage.DONE, Stage.EXECUTION),
            Stage.DONE to emptySet(),
        )

        Stage.entries.forEach { from ->
            // when
            val to = machine.allowedNext(from)

            // then
            assertEquals(table[from], to, "allowedNext($from)")
        }
    }

    @Test
    fun `when canTransition along the forward path - then allowed`() {
        // given
        val forward = listOf(
            Stage.CLARIFICATION to Stage.PLANNING,
            Stage.PLANNING to Stage.EXECUTION,
            Stage.EXECUTION to Stage.VALIDATION,
            Stage.VALIDATION to Stage.DONE,
        )

        // when - then
        forward.forEach { (from, to) ->
            assertTrue(machine.canTransition(from, to), "$from -> $to should be allowed")
        }
    }

    @Test
    fun `when canTransition steps back one stage - then allowed`() {
        // given
        val back = listOf(
            Stage.PLANNING to Stage.CLARIFICATION,
            Stage.EXECUTION to Stage.PLANNING,
            Stage.VALIDATION to Stage.EXECUTION,
        )

        // when - then
        back.forEach { (from, to) ->
            assertTrue(machine.canTransition(from, to), "$from -> $to should be allowed")
        }
    }

    @Test
    fun `when canTransition skips a stage forward - then rejected`() {
        // given
        val illegal = listOf(
            Stage.CLARIFICATION to Stage.EXECUTION,
            Stage.CLARIFICATION to Stage.DONE,
            Stage.PLANNING to Stage.VALIDATION,
            Stage.PLANNING to Stage.DONE,
            Stage.EXECUTION to Stage.DONE,
        )

        // when - then
        illegal.forEach { (from, to) ->
            assertFalse(machine.canTransition(from, to), "$from -> $to should be rejected")
        }
    }

    @Test
    fun `when canTransition steps back over a stage - then rejected`() {
        // given
        val illegal = listOf(
            Stage.EXECUTION to Stage.CLARIFICATION,
            Stage.VALIDATION to Stage.PLANNING,
            Stage.VALIDATION to Stage.CLARIFICATION,
        )

        // when - then
        illegal.forEach { (from, to) ->
            assertFalse(machine.canTransition(from, to), "$from -> $to should be rejected")
        }
    }

    @Test
    fun `when canTransition from done - then every target is rejected`() {
        // when - then
        Stage.entries.forEach { to ->
            assertFalse(machine.canTransition(Stage.DONE, to), "done -> $to should be rejected")
        }
    }
}

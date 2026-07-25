package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskTransitionsTest {

    @Test
    fun `when allowedNext queried - then matches the transition table`() {
        // given
        // The full forward + single-step-back table; done is terminal.
        val table = mapOf(
            Stage.CLARIFICATION to setOf(Stage.PLANNING),
            Stage.PLANNING to setOf(Stage.EXECUTION, Stage.CLARIFICATION),
            Stage.EXECUTION to setOf(Stage.VALIDATION, Stage.PLANNING),
            Stage.VALIDATION to setOf(Stage.DONE, Stage.EXECUTION),
            Stage.DONE to emptySet(),
        )

        // when - then
        // forEach justified (rule §11.E): one invariant — "allowedNext matches the
        // table" — over every enum value; per-case message pinpoints a miss.
        Stage.entries.forEach { from ->
            assertEquals(table[from], TaskStateMachine.allowedNext(from), "allowedNext($from)")
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
            assertTrue(TaskStateMachine.canTransition(from, to), "$from -> $to should be allowed")
        }
    }

    @Test
    fun `when canTransition steps back one stage - then allowed`() {
        // given
        // A plan that turns out wrong is not a dead end; the FSM lets the task
        // revisit the prior phase rather than restart.
        val back = listOf(
            Stage.PLANNING to Stage.CLARIFICATION,
            Stage.EXECUTION to Stage.PLANNING,
            Stage.VALIDATION to Stage.EXECUTION,
        )

        // when - then
        back.forEach { (from, to) ->
            assertTrue(TaskStateMachine.canTransition(from, to), "$from -> $to should be allowed")
        }
    }

    @Test
    fun `when canTransition skips a stage - then rejected`() {
        // given
        val illegal = listOf(
            Stage.CLARIFICATION to Stage.EXECUTION,
            Stage.CLARIFICATION to Stage.DONE,
            Stage.PLANNING to Stage.VALIDATION,
            Stage.PLANNING to Stage.DONE,
        )

        // when - then
        illegal.forEach { (from, to) ->
            assertFalse(TaskStateMachine.canTransition(from, to), "$from -> $to should be rejected")
        }
    }

    @Test
    fun `when canTransition from a null stage - then any target initializes`() {
        // given
        // A task with no stage yet: nothing to violate, so any stage is a valid start.

        // when - then
        Stage.entries.forEach { to ->
            assertTrue(TaskStateMachine.canTransition(null, to), "null -> $to should initialize")
        }
    }

    @Test
    fun `when canTransition from done - then every target is rejected`() {
        // given
        // done is terminal — a finished task is not reopened automatically.

        // when - then
        Stage.entries.forEach { to ->
            assertFalse(TaskStateMachine.canTransition(Stage.DONE, to), "done -> $to should be rejected")
        }
    }
}

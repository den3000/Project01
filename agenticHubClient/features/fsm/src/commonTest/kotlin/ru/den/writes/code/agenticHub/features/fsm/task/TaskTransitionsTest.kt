package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.AdvanceOutcome
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachineImpl
import ru.den.writes.code.agenticHub.features.fsm.UpdateDecision
import ru.den.writes.code.agenticHub.features.fsm.UpdateReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The transition table, read the only way anyone outside can read it: by proposing a move and
 * seeing whether it was applied or refused. Where a stage may go is checked from the same answer
 * — that list is what the next prompt quotes back.
 */
class TaskTransitionsTest {

    private val machine = TaskStateMachineImpl()

    @Test
    fun `when a stage names itself - then the onward stages match the transition table`() {
        // given
        val table = mapOf(
            Stage.CLARIFICATION to setOf(Stage.PLANNING),
            Stage.PLANNING to setOf(Stage.EXECUTION, Stage.CLARIFICATION),
            Stage.EXECUTION to setOf(Stage.VALIDATION, Stage.PLANNING),
            Stage.VALIDATION to setOf(Stage.DONE, Stage.EXECUTION),
            Stage.DONE to emptySet(),
        )

        Stage.entries.forEach { from ->
            // when — a re-signal leaves the task where it stands, so the answer is `from`'s row
            val actual = propose(from, from)

            // then
            assertEquals(table[from], actual.allowedNext, "allowedNext($from)")
        }
    }

    @Test
    fun `when the move follows the forward path - then it is applied`() {
        // given
        val forward = listOf(
            Stage.CLARIFICATION to Stage.PLANNING,
            Stage.PLANNING to Stage.EXECUTION,
            Stage.EXECUTION to Stage.VALIDATION,
            Stage.VALIDATION to Stage.DONE,
        )

        // when - then
        forward.forEach { (from, to) ->
            assertIs<AdvanceOutcome.Advanced>(propose(from, to).advance, "$from -> $to should be allowed")
        }
    }

    @Test
    fun `when the move steps back one stage - then it is applied`() {
        // given
        val back = listOf(
            Stage.PLANNING to Stage.CLARIFICATION,
            Stage.EXECUTION to Stage.PLANNING,
            Stage.VALIDATION to Stage.EXECUTION,
        )

        // when - then
        back.forEach { (from, to) ->
            assertIs<AdvanceOutcome.Advanced>(propose(from, to).advance, "$from -> $to should be allowed")
        }
    }

    @Test
    fun `when the move skips a stage forward - then it is refused`() {
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
            assertIs<AdvanceOutcome.Rejected>(propose(from, to).advance, "$from -> $to should be rejected")
        }
    }

    @Test
    fun `when the move steps back over a stage - then it is refused`() {
        // given
        val illegal = listOf(
            Stage.EXECUTION to Stage.CLARIFICATION,
            Stage.VALIDATION to Stage.PLANNING,
            Stage.VALIDATION to Stage.CLARIFICATION,
        )

        // when - then
        illegal.forEach { (from, to) ->
            assertIs<AdvanceOutcome.Rejected>(propose(from, to).advance, "$from -> $to should be rejected")
        }
    }

    @Test
    fun `when a done task is moved anywhere - then every target is refused`() {
        // when - then — naming done again is a re-signal, not a move, so it is asked separately
        Stage.entries.filter { it != Stage.DONE }.forEach { to ->
            assertIs<AdvanceOutcome.Rejected>(propose(Stage.DONE, to).advance, "done -> $to should be rejected")
        }
    }

    private fun propose(from: Stage, to: Stage): UpdateDecision =
        machine.update(task(stage = from, deepestStage = from), UpdateReason.StageProposed(to))
}

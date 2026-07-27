package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.AdvanceOutcome
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachineImpl
import ru.den.writes.code.agenticHub.features.fsm.UpdateReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A task that already reached [Stage.DONE]: the turns that keep arriving cost it nothing.
 *
 * Its own grain, because the rule cuts across every other file — whatever a turn ended in,
 * a finished task is charged nothing for it. The failure it prevents was measured live: a
 * headless run kept feeding "continue" after the task delivered, the stage budget ran out on
 * the chatter, and the machine restarted a task that had already succeeded.
 */
class TaskTerminalTest {

    private val machine = TaskStateMachineImpl()

    @Test
    fun `when the task is done - then no turn costs it anything`() {
        // given — every way a turn can end, including the moves the table would refuse
        val task = task(stage = Stage.DONE, deepestStage = Stage.DONE)
        val reasons = listOf(
            UpdateReason.StageProposed(Stage.DONE),
            UpdateReason.StageProposed(Stage.PLANNING),
            UpdateReason.NoStageProposed,
            UpdateReason.JudgeBlocked,
            UpdateReason.TransportFailed,
        )

        reasons.forEach { reason ->
            // when
            val actual = machine.update(task, reason)

            // then — the task comes back exactly as it went in, and nothing was decided about it
            assertEquals(task, actual.task, "task($reason)")
            assertNull(actual.retryOutcome, "outcome($reason)")
            assertNull(actual.retryReason, "charged($reason)")
            assertTrue(actual.allowedNext.isEmpty(), "allowedNext($reason)")
        }
    }

    @Test
    fun `when the task is done and the model signals done again - then the repeat is still reported`() {
        // given
        val task = task(stage = Stage.DONE, deepestStage = Stage.DONE)

        // when
        val actual = machine.update(task, UpdateReason.StageProposed(Stage.DONE))

        // then — a view can say what the model did; the price the outcome names is not taken
        val advance = assertIs<AdvanceOutcome.Repeated>(actual.advance)
        assertEquals(Stage.DONE, advance.stage)
        assertNull(actual.retryReason)
    }

    @Test
    fun `when the task is done and any other stage is proposed - then the move is refused for free`() {
        // given
        val task = task(stage = Stage.DONE, deepestStage = Stage.DONE)
        val targets = Stage.entries.filter { it != Stage.DONE }

        targets.forEach { to ->
            // when
            val actual = machine.update(task, UpdateReason.StageProposed(to))

            // then — refused like any illegal move, but the refusal costs a finished task nothing
            val advance = assertIs<AdvanceOutcome.Rejected>(actual.advance, "outcome(done -> $to)")
            assertEquals(Stage.DONE, advance.from, "from(done -> $to)")
            assertEquals(to, advance.proposed, "proposed(done -> $to)")
            assertEquals(emptySet(), advance.allowed, "allowed(done -> $to)")
            assertEquals(task, actual.task, "task(done -> $to)")
            assertNull(actual.retryOutcome, "outcome(done -> $to)")
        }
    }
}

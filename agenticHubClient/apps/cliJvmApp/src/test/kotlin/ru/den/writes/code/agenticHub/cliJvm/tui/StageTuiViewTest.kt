package ru.den.writes.code.agenticHub.cliJvm.tui

import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.StageAdvance
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The task-stage FSM line in the TUI column — the same four outcomes the plain front renders. */
class StageTuiViewTest {

    @Test
    fun `when advanced - then the auto stage line`() {
        assertEquals(
            "stage: planning → execution (auto)",
            stageLine(StageAdvance.Advanced(TaskStage.PLANNING, TaskStage.EXECUTION)),
        )
    }

    @Test
    fun `when rejected - then the not-allowed line names the allowed set`() {
        assertEquals(
            "model proposed planning → done, not allowed (allowed: execution) — ignored",
            stageLine(StageAdvance.Rejected(TaskStage.PLANNING, TaskStage.DONE, setOf(TaskStage.EXECUTION))),
        )
    }

    @Test
    fun `when repeated - then the already-there line`() {
        assertEquals(
            "model re-signalled validation — already there, no move",
            stageLine(StageAdvance.Repeated(TaskStage.VALIDATION, setOf(TaskStage.DONE, TaskStage.EXECUTION))),
        )
    }

    @Test
    fun `when none - then nothing`() {
        assertNull(stageLine(StageAdvance.None))
    }
}

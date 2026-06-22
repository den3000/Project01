package ru.den.writes.code.project01.cliJvm.plain

import ru.den.writes.code.project01.cliJvm.StageAdvance
import ru.den.writes.code.project01.shared.memory.TaskStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The task-stage FSM line on stderr. */
class StagePlainViewTest {

    @Test
    fun `when advanced - then the auto stage line`() {
        assertEquals(
            listOf("[task] stage: planning → execution (auto)"),
            StagePlainView(StageAdvance.Advanced(TaskStage.PLANNING, TaskStage.EXECUTION)).stderr(),
        )
    }

    @Test
    fun `when rejected - then the not-allowed line names the allowed set`() {
        assertEquals(
            listOf("[task] model proposed planning → done, not allowed (allowed: execution) — ignored"),
            StagePlainView(
                StageAdvance.Rejected(TaskStage.PLANNING, TaskStage.DONE, setOf(TaskStage.EXECUTION)),
            ).stderr(),
        )
    }

    @Test
    fun `when none - then nothing`() {
        assertTrue(StagePlainView(StageAdvance.None).stderr().isEmpty())
    }
}

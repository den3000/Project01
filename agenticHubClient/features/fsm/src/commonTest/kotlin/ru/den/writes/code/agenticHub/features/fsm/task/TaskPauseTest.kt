package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.AdvanceOutcome
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TaskPauseTest {

    @Test
    fun `when a paused task is offered a legal move - then it is held`() {
        // given
        val task = task(stage = Stage.EXECUTION, paused = true, notes = listOf("scope agreed"))
        val proposed = Stage.VALIDATION

        // when
        val actual = TaskStateMachine.advance(task, proposed)

        // then
        assertIs<AdvanceOutcome.Held>(actual)
        assertEquals(task, actual.task)
        assertNull(actual.reason)
    }

    @Test
    fun `when a paused task is offered an illegal move - then it is held`() {
        // given
        val task = task(stage = Stage.EXECUTION, paused = true, notes = listOf("scope agreed"))
        val proposed = Stage.DONE

        // when
        val actual = TaskStateMachine.advance(task, proposed)

        // then
        assertIs<AdvanceOutcome.Held>(actual)
        assertEquals(task, actual.task)
        assertNull(actual.reason)
    }

    @Test
    fun `when a paused task is offered the stage it is already in - then it is held`() {
        // given
        val stage = Stage.EXECUTION
        val task = task(stage = stage, paused = true, notes = listOf("scope agreed"))

        // when
        val actual = TaskStateMachine.advance(task, stage)

        // then
        assertIs<AdvanceOutcome.Held>(actual)
        assertEquals(task, actual.task)
        assertNull(actual.reason)
    }
}

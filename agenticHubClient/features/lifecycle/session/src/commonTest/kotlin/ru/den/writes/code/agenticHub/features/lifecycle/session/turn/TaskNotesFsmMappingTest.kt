package ru.den.writes.code.agenticHub.features.lifecycle.session.turn

import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskNotesFsmMappingTest {

    @Test
    fun `when stored notes go through the machine and back - then nothing is lost`() {
        // given
        val notes = TaskNotes(
            taskId = "auth-service",
            goal = "Сервис авторизации поверх Ktor",
            stage = TaskStage.EXECUTION,
            deepestStage = TaskStage.VALIDATION,
            paused = true,
            notes = listOf("Ktor 3", "без Spring"),
            taskRetriesSpent = 2,
            stageRetriesSpent = 7,
            transportRetriesSpent = 1,
        )

        // when
        val actual = notes.withFsmTask(notes.toFsmTask())

        // then
        assertEquals(notes, actual)
    }

    @Test
    fun `when spent counters are read - then the ceilings come from the machine`() {
        // given
        val notes = TaskNotes(taskId = "t", taskRetriesSpent = 2, stageRetriesSpent = 7, transportRetriesSpent = 1)

        // when
        val actual = notes.toFsmTask()

        // then
        assertEquals(RetryState(attempt = 2, max = RetryState.TASK_MAX), actual.taskRetryState)
        assertEquals(RetryState(attempt = 7, max = RetryState.STAGE_MAX), actual.stageRetryState)
        assertEquals(RetryState(attempt = 1, max = RetryState.TRANSPORT_MAX), actual.transportRetryState)
    }

    @Test
    fun `when the stored task has no recorded depth - then it counts as having reached its stage`() {
        // given
        // A file written before the depth existed. Reading it as shallower would hand a
        // task that is already oscillating a free budget refresh on its next move.
        val notes = TaskNotes(taskId = "t", stage = TaskStage.VALIDATION, deepestStage = null)

        // when
        val actual = notes.toFsmTask()

        // then
        assertEquals(Stage.VALIDATION, actual.deepestStage)
    }

    @Test
    fun `when the stored task has no stage - then the machine starts it at the beginning`() {
        // given
        val notes = TaskNotes(taskId = "t", stage = null)

        // when
        val actual = notes.toFsmTask()

        // then
        assertEquals(Stage.INITIAL, actual.stage)
    }

    @Test
    fun `when a stage crosses the boundary - then it is matched by keyword in both directions`() {
        // given
        val stored = TaskStage.entries

        // when
        val actuals = stored.map { it to it.toFsmStage() }

        // then
        actuals.forEach { (stage, mapped) ->
            assertEquals(stage.keyword, mapped.keyword, "keyword(${stage.keyword})")
            assertEquals(stage, mapped.toTaskStage(), "round-trip(${stage.keyword})")
        }
    }
}

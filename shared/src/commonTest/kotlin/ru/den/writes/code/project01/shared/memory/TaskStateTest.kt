package ru.den.writes.code.project01.shared.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskStateTest {

    //region transition table & validation

    @Test
    fun `when allowedNext queried - then matches the transition table`() {
        // given
        // The full forward + single-step-back table; done is terminal.
        val table = mapOf(
            TaskStage.CLARIFICATION to setOf(TaskStage.PLANNING),
            TaskStage.PLANNING to setOf(TaskStage.EXECUTION, TaskStage.CLARIFICATION),
            TaskStage.EXECUTION to setOf(TaskStage.VALIDATION, TaskStage.PLANNING),
            TaskStage.VALIDATION to setOf(TaskStage.DONE, TaskStage.EXECUTION),
            TaskStage.DONE to emptySet(),
        )

        // when - then
        // forEach justified (rule §11.E): one invariant — "allowedNext matches
        // the table" — over every enum value; per-case message pinpoints a miss.
        table.forEach { (from, expected) ->
            assertEquals(expected, TaskStateMachine.allowedNext(from), "allowedNext($from)")
        }
    }

    @Test
    fun `when canTransition along the forward path - then allowed`() {
        // given
        val forward = listOf(
            TaskStage.CLARIFICATION to TaskStage.PLANNING,
            TaskStage.PLANNING to TaskStage.EXECUTION,
            TaskStage.EXECUTION to TaskStage.VALIDATION,
            TaskStage.VALIDATION to TaskStage.DONE,
        )

        // when - then
        forward.forEach { (from, to) ->
            assertTrue(TaskStateMachine.canTransition(from, to), "$from -> $to should be allowed")
        }
    }

    @Test
    fun `when canTransition steps back one stage - then allowed`() {
        // given
        val back = listOf(
            TaskStage.PLANNING to TaskStage.CLARIFICATION,
            TaskStage.EXECUTION to TaskStage.PLANNING,
            TaskStage.VALIDATION to TaskStage.EXECUTION,
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
            TaskStage.CLARIFICATION to TaskStage.EXECUTION,
            TaskStage.CLARIFICATION to TaskStage.DONE,
            TaskStage.PLANNING to TaskStage.VALIDATION,
            TaskStage.PLANNING to TaskStage.DONE,
        )

        // when - then
        illegal.forEach { (from, to) ->
            assertFalse(TaskStateMachine.canTransition(from, to), "$from -> $to should be rejected")
        }
    }

    @Test
    fun `when canTransition from a null stage - then any target initializes`() {
        // given
        // A task with no stage yet (legacy / hand-edited file): nothing to
        // violate, so initialization to any stage is permitted.

        // when - then
        TaskStage.entries.forEach { to ->
            assertTrue(TaskStateMachine.canTransition(null, to), "null -> $to should initialize")
        }
    }

    @Test
    fun `when canTransition from done - then every target is rejected`() {
        // given
        // done is terminal — allowedNext is empty.

        // when - then
        TaskStage.entries.forEach { to ->
            assertFalse(TaskStateMachine.canTransition(TaskStage.DONE, to), "done -> $to should be rejected")
        }
    }
    //endregion

    //region keyword mapping

    @Test
    fun `when byKeyword given each stage keyword - then that stage`() {
        // when - then
        TaskStage.entries.forEach { stage ->
            assertEquals(stage, TaskStage.byKeyword(stage.keyword), "byKeyword(${stage.keyword})")
        }
    }

    @Test
    fun `when byKeyword given an unknown keyword - then null`() {
        // given
        val token = "shipping"

        // when
        val actual = TaskStage.byKeyword(token)

        // then
        assertNull(actual)
    }

    @Test
    fun `when byKeyword given a padded uppercase keyword - then matched case-insensitively`() {
        // given
        val token = "  EXECUTION  "

        // when
        val actual = TaskStage.byKeyword(token)

        // then
        assertEquals(TaskStage.EXECUTION, actual)
    }

    @Test
    fun `when INITIAL read - then clarification`() {
        // when
        val actual = TaskStage.INITIAL

        // then
        assertEquals(TaskStage.CLARIFICATION, actual)
    }
    //endregion

    //region stage signal parsing

    @Test
    fun `when parseStageSignal sees a marker in prose - then that stage`() {
        // given
        val reply = "Requirements look complete.\n[[stage:planning]]"

        // when
        val actual = TaskStateMachine.parseStageSignal(reply)

        // then
        assertEquals(TaskStage.PLANNING, actual)
    }

    @Test
    fun `when parseStageSignal marker has spaces and uppercase - then that stage`() {
        // given
        val reply = "done here [[ STAGE : Execution ]] trailing"

        // when
        val actual = TaskStateMachine.parseStageSignal(reply)

        // then
        assertEquals(TaskStage.EXECUTION, actual)
    }

    @Test
    fun `when parseStageSignal sees no marker - then null`() {
        // given
        val reply = "Just a normal answer with no control marker."

        // when
        val actual = TaskStateMachine.parseStageSignal(reply)

        // then
        assertNull(actual)
    }

    @Test
    fun `when parseStageSignal marker keyword is unknown - then null`() {
        // given
        val reply = "[[stage:shipping]]"

        // when
        val actual = TaskStateMachine.parseStageSignal(reply)

        // then
        assertNull(actual)
    }

    @Test
    fun `when parseStageSignal sees several markers - then the last one`() {
        // given
        // The model reconsidered mid-reply; its final word wins.
        val reply = "[[stage:planning]] ... actually [[stage:execution]]"

        // when
        val actual = TaskStateMachine.parseStageSignal(reply)

        // then
        assertEquals(TaskStage.EXECUTION, actual)
    }
    //endregion

    //region stage binding

    @Test
    fun `when contains queried across the span - then only in-range stages match`() {
        // given
        val binding = TaskBinding(TaskStage.PLANNING, TaskStage.VALIDATION)
        val inRange = setOf(TaskStage.PLANNING, TaskStage.EXECUTION, TaskStage.VALIDATION)

        // when - then
        // One invariant — "in binding == ordinal within from..to" — over every stage.
        TaskStage.entries.forEach { stage ->
            assertEquals(stage in inRange, stage in binding, "contains($stage)")
        }
    }

    @Test
    fun `when from equals to - then only that single stage is in the span`() {
        // given
        val binding = TaskBinding(TaskStage.EXECUTION, TaskStage.EXECUTION)

        // when - then
        TaskStage.entries.forEach { stage ->
            assertEquals(stage == TaskStage.EXECUTION, stage in binding, "contains($stage)")
        }
    }

    @Test
    fun `when from is after to - then constructing throws`() {
        // when - then
        assertFailsWith<IllegalArgumentException> {
            TaskBinding(TaskStage.EXECUTION, TaskStage.PLANNING)
        }
    }
    //endregion
}

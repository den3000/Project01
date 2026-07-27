package ru.den.writes.code.agenticHub.features.lifecycle.session.turn

import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryFeedbackTest {

    @Test
    fun `when a retry is charged - then every reason either speaks or is silent by design`() {
        // given
        val speaks = mapOf(
            RetryReason.NO_MARKER to true,
            RetryReason.STAGE_REPEATED to true,
            RetryReason.STAGE_REJECTED to true,
            RetryReason.STAGE_REVISITED to false,
            RetryReason.JUDGE_BLOCKED to true,
            RetryReason.JUDGE_REWRITE to false,
            RetryReason.TRANSPORT_FAILED to false,
            RetryReason.TASK_STALLED to false,
            RetryReason.USER_RESTART to false,
        )

        // when
        val actuals = RetryReason.entries.map { reason ->
            reason to retryFeedbackMessage(
                feedback = RetryFeedback(
                    reason,
                    proposed = TaskStage.DONE,
                    allowed = setOf(TaskStage.VALIDATION, TaskStage.PLANNING),
                ),
                stage = TaskStage.EXECUTION,
                spent = 1,
            )
        }

        // then
        actuals.forEach { (reason, actual) ->
            assertEquals(speaks[reason], actual != null, "speaks($reason)")
        }
    }

    @Test
    fun `when a stage stops moving for the first time - then the model is told the marker did nothing`() {
        // given
        val allowed = setOf(TaskStage.VALIDATION, TaskStage.PLANNING)

        // when
        val actual = retryFeedbackMessage(
            feedback = RetryFeedback(RetryReason.NO_MARKER, allowed = allowed),
            stage = TaskStage.EXECUTION,
            spent = 1,
        )

        // then
        assertNotNull(actual)
        assertTrue(FSM_NO_MOVE in actual.text, actual.text)
        allowed.forEach { assertTrue(it.keyword in actual.text, "${it.keyword} in ${actual.text}") }
    }

    @Test
    fun `when a stage keeps not moving - then the plain line gives way to the nudge`() {
        // given
        val feedback = RetryFeedback(
            RetryReason.NO_MARKER,
            allowed = setOf(TaskStage.VALIDATION, TaskStage.PLANNING),
        )

        // when
        val actual = retryFeedbackMessage(feedback = feedback, stage = TaskStage.EXECUTION, spent = 2)

        // then
        assertNotNull(actual)
        assertTrue(FSM_STALLED in actual.text, actual.text)
    }

    @Test
    fun `when a move was refused - then the message names what was asked for`() {
        // given
        val feedback = RetryFeedback(
            RetryReason.STAGE_REJECTED,
            proposed = TaskStage.DONE,
            allowed = setOf(TaskStage.EXECUTION, TaskStage.CLARIFICATION),
        )

        // when
        val actual = retryFeedbackMessage(feedback = feedback, stage = TaskStage.PLANNING, spent = 1)

        // then
        assertNotNull(actual)
        assertTrue(TaskStage.DONE.keyword in actual.text, actual.text)
        assertTrue(TaskStage.PLANNING.keyword in actual.text, actual.text)
    }

    @Test
    fun `when a refusal has no proposal to quote - then nothing is said`() {
        // given
        val feedback = RetryFeedback(
            RetryReason.STAGE_REJECTED,
            proposed = null,
            allowed = setOf(TaskStage.EXECUTION),
        )

        // when
        val actual = retryFeedbackMessage(feedback = feedback, stage = TaskStage.PLANNING, spent = 1)

        // then
        assertNull(actual)
    }

    @Test
    fun `when the auditor withdrew the reply - then the model is told it never landed`() {
        // given
        val feedback = RetryFeedback(RetryReason.JUDGE_BLOCKED, allowed = setOf(TaskStage.VALIDATION))

        // when
        val actual = retryFeedbackMessage(feedback = feedback, stage = TaskStage.EXECUTION, spent = 1)

        // then
        assertNotNull(actual)
        assertTrue("withdrawn" in actual.text, actual.text)
        assertTrue("not part of this conversation" in actual.text, actual.text)
    }
}

package ru.den.writes.code.agenticHub.cliJvm.plain

import ru.den.writes.code.agenticHub.features.agent.invariant.InvariantVerdict
import ru.den.writes.code.agenticHub.features.agent.invariant.InvariantViolation
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.JudgeOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The invariant-judge lines on stderr, and the trailer naming what each outcome cost. */
class JudgePlainViewTest {

    private val breached = InvariantVerdict(
        passed = false,
        violations = listOf(InvariantViolation("001", "proposes Spring"), InvariantViolation(null, "off topic")),
    )

    @Test
    fun `when the turn is blocked on one attempt - then each objection is listed unnumbered`() {
        // given — a single rejected attempt: a numbered rule breach and an unnumbered constraint breach
        val view = JudgePlainView(JudgeOutcome.Blocked(listOf(breached)))

        // when - then — one attempt reads exactly as before, no "attempt N" prefix; null ruleId → "constraint"
        assertEquals(
            listOf(
                "[invariant] violated 001: proposes Spring",
                "[invariant] violated constraint: off topic",
                "[invariant] reply not saved to history; task stage held",
            ),
            view.stderr(),
        )
    }

    @Test
    fun `when the turn is blocked after several attempts - then each attempt's objections are numbered`() {
        // given — the judge rejected three rewrites in a row
        val second = InvariantVerdict(false, listOf(InvariantViolation("001", "still proposes Spring")))
        val third = InvariantVerdict(false, listOf(InvariantViolation(null, "invented a file")))
        val view = JudgePlainView(JudgeOutcome.Blocked(listOf(breached, second, third)))

        // when - then — the number is how the reader sees whether the rewrites made any progress
        assertEquals(
            listOf(
                "[invariant] attempt 1 violated 001: proposes Spring",
                "[invariant] attempt 1 violated constraint: off topic",
                "[invariant] attempt 2 violated 001: still proposes Spring",
                "[invariant] attempt 3 violated constraint: invented a file",
                "[invariant] reply not saved to history; task stage held",
            ),
            view.stderr(),
        )
    }

    @Test
    fun `when a retry satisfied the judge - then the objections stay but the trailer says rewrite`() {
        // given — the earlier answer was withdrawn, the shown reply is the rewrite
        val view = JudgePlainView(JudgeOutcome.Retried(listOf(breached)))

        // when - then — the trailer must name whose text the objections are about, or they
        // read as a complaint against the reply on screen, which in fact passed
        assertEquals(
            listOf(
                "[invariant] violated 001: proposes Spring",
                "[invariant] violated constraint: off topic",
                "[invariant] objections above are about WITHDRAWN earlier replies; " +
                    "the answer shown is the agent's rewrite, which passed",
            ),
            view.stderr(),
        )
    }

    @Test
    fun `when the judge found nothing - then it says so instead of staying silent`() {
        // given - when - then — silence would be indistinguishable from a judge that never ran
        assertEquals(
            listOf("[invariant] clean — no objection to this turn"),
            JudgePlainView(JudgeOutcome.Clean).stderr(),
        )
    }

    @Test
    fun `when no judge covered the stage - then nothing on stderr`() {
        assertTrue(JudgePlainView(JudgeOutcome.NotRun).stderr().isEmpty())
    }
}

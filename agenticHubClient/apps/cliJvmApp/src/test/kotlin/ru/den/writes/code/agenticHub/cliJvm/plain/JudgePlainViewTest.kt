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
    fun `when the turn is blocked - then each objection is listed and the held-stage trailer closes`() {
        // given — a numbered rule breach and an unnumbered constraint breach
        val view = JudgePlainView(JudgeOutcome.Blocked(breached))

        // when - then — null ruleId renders as "constraint"
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
    fun `when a retry satisfied the judge - then the objections stay but the trailer says rewrite`() {
        // given — the first answer was withdrawn, the shown reply is the rewrite
        val view = JudgePlainView(JudgeOutcome.Retried(breached))

        // when - then — the trailer must name whose text the objections are about, or they
        // read as a complaint against the reply on screen, which in fact passed
        assertEquals(
            listOf(
                "[invariant] violated 001: proposes Spring",
                "[invariant] violated constraint: off topic",
                "[invariant] objections above are about the WITHDRAWN first reply; " +
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

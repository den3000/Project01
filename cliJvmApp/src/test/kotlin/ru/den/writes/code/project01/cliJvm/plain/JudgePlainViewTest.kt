package ru.den.writes.code.project01.cliJvm.plain

import ru.den.writes.code.project01.shared.invariant.InvariantViolation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The invariant-judge breach lines on stderr. */
class JudgePlainViewTest {

    @Test
    fun `when violations present - then each is listed and the trailer closes`() {
        // given — a numbered rule breach and an unnumbered constraint breach
        val view = JudgePlainView(
            listOf(InvariantViolation("001", "proposes Spring"), InvariantViolation(null, "off topic")),
        )

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
    fun `when no violations - then nothing on stderr`() {
        assertTrue(JudgePlainView(emptyList()).stderr().isEmpty())
    }
}

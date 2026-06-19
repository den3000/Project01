package ru.den.writes.code.project01.shared.invariant

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.shared.llm.FakeLlmApi
import ru.den.writes.code.project01.shared.llm.LlmResult
import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.memory.RuleEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmInvariantJudgeTest {

    private val rules = listOf(RuleEntry("001", "Kotlin only, no Spring"))

    @Test
    fun `when check - then a single independent user turn is sent`() = runTest {
        // given
        val api = FakeLlmApi().apply { queueText("""{"passed": true, "violations": []}""") }

        // when
        LlmInvariantJudge(api).check("some reply", rules, constraints = emptyList())

        // then — exactly the judge prompt, no history / memory layer
        val sent = api.calls.single().messages
        assertEquals(1, sent.size)
        assertEquals(Role.USER, sent.single().role)
        assertTrue(sent.single().text.contains("001"), "judge prompt should carry the rules")
    }

    @Test
    fun `when judge reports a violation - then verdict is not passed`() = runTest {
        // given
        val api = FakeLlmApi().apply {
            queueText("""{"passed": false, "violations": [{"ruleId": "001", "explanation": "Spring"}]}""")
        }

        // when
        val actual = LlmInvariantJudge(api).check("Use Spring", rules, constraints = emptyList())

        // then
        assertFalse(actual.passed)
        assertEquals("001", actual.violations.single().ruleId)
    }

    @Test
    fun `when the judge call errors - then clean (fail-open)`() = runTest {
        // given
        val api = FakeLlmApi().apply { queue(LlmResult(text = null, error = "boom")) }

        // when
        val actual = LlmInvariantJudge(api).check("anything", rules, constraints = emptyList())

        // then
        assertTrue(actual.passed)
    }

    @Test
    fun `when no rules and no constraints - then no wire call`() = runTest {
        // given
        val api = FakeLlmApi()

        // when
        val actual = LlmInvariantJudge(api).check("reply", rules = emptyList(), constraints = emptyList())

        // then
        assertTrue(actual.passed)
        assertTrue(api.calls.isEmpty(), "judge must not call the model when there is nothing to enforce")
    }

    @Test
    fun `when only constraints present - then judge still runs`() = runTest {
        // given
        val api = FakeLlmApi().apply {
            queueText("""{"passed": false, "violations": [{"ruleId": null, "explanation": "RxJava"}]}""")
        }

        // when
        val actual = LlmInvariantJudge(api).check("Use RxJava", rules = emptyList(), constraints = listOf("no RxJava"))

        // then
        assertEquals(1, api.calls.size)
        assertFalse(actual.passed)
    }

    @Test
    fun `when judge returns prose instead of json - then clean (fail-open)`() = runTest {
        // given — the judge babbled instead of returning a JSON verdict
        val api = FakeLlmApi().apply { queueText("The reply looks fine to me, no issues.") }

        // when
        val actual = LlmInvariantJudge(api).check("anything", rules, constraints = emptyList())

        // then
        assertTrue(actual.passed)
    }
}

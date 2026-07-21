package ru.den.writes.code.agenticHub.features.agent.invariant

import ru.den.writes.code.agenticHub.features.llm.di.llmTestModule
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.FakeLlmScript
import org.koin.dsl.koinApplication
import org.koin.core.parameter.parametersOf
import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.llm.LlmResult
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.memory.RuleEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmInvariantJudgeTest {

    private fun scriptedApi(script: FakeLlmScript): LlmApi =
        koinApplication { modules(llmTestModule) }.koin.get { parametersOf(script) }


    private val rules = listOf(RuleEntry("001", "Kotlin only, no Spring"))

    @Test
    fun `when check - then a single independent user turn is sent`() = runTest {
        // given
        val apiScript = FakeLlmScript().apply { queueText("""{"passed": true, "violations": []}""") }
        val api = scriptedApi(apiScript)

        // when
        LlmInvariantJudge(api).check(JudgeInput("some reply", rules, constraints = emptyList()))

        // then — exactly the judge prompt, no history / memory layer
        val sent = apiScript.calls.single().messages
        assertEquals(1, sent.size)
        assertEquals(Role.USER, sent.single().role)
        assertTrue(sent.single().text.contains("001"), "judge prompt should carry the rules")
    }

    @Test
    fun `when judge reports a violation - then verdict is not passed`() = runTest {
        // given
        val apiScript = FakeLlmScript().apply {
            queueText("""{"passed": false, "violations": [{"ruleId": "001", "explanation": "Spring"}]}""")
        }
        val api = scriptedApi(apiScript)

        // when
        val actual = LlmInvariantJudge(api).check(JudgeInput("Use Spring", rules, constraints = emptyList()))

        // then
        assertFalse(actual.passed)
        assertEquals("001", actual.violations.single().ruleId)
    }

    @Test
    fun `when the judge call errors - then clean (fail-open)`() = runTest {
        // given
        val apiScript = FakeLlmScript().apply { queue(LlmResult(text = null, error = "boom")) }
        val api = scriptedApi(apiScript)

        // when
        val actual = LlmInvariantJudge(api).check(JudgeInput("anything", rules, constraints = emptyList()))

        // then
        assertTrue(actual.passed)
    }

    @Test
    fun `when no rules and no constraints - then no wire call`() = runTest {
        // given
        val apiScript = FakeLlmScript()
        val api = scriptedApi(apiScript)

        // when
        val actual = LlmInvariantJudge(api).check(JudgeInput("reply", rules = emptyList(), constraints = emptyList()))

        // then
        assertTrue(actual.passed)
        assertTrue(apiScript.calls.isEmpty(), "judge must not call the model when there is nothing to enforce")
    }

    @Test
    fun `when only constraints present - then judge still runs`() = runTest {
        // given
        val apiScript = FakeLlmScript().apply {
            queueText("""{"passed": false, "violations": [{"ruleId": null, "explanation": "RxJava"}]}""")
        }
        val api = scriptedApi(apiScript)

        // when
        val actual = LlmInvariantJudge(api)
            .check(JudgeInput("Use RxJava", rules = emptyList(), constraints = listOf("no RxJava")))

        // then
        assertEquals(1, apiScript.calls.size)
        assertFalse(actual.passed)
    }

    @Test
    fun `when judge returns prose instead of json - then clean (fail-open)`() = runTest {
        // given — the judge babbled instead of returning a JSON verdict
        val apiScript = FakeLlmScript().apply { queueText("The reply looks fine to me, no issues.") }
        val api = scriptedApi(apiScript)

        // when
        val actual = LlmInvariantJudge(api).check(JudgeInput("anything", rules, constraints = emptyList()))

        // then
        assertTrue(actual.passed)
    }

    @Test
    fun `when check runs - then thinking is disabled and the budget is reserved for json`() = runTest {
        // given
        val apiScript = FakeLlmScript().apply { queueText("""{"passed": true, "violations": []}""") }
        val api = scriptedApi(apiScript)

        // when
        LlmInvariantJudge(api).check(JudgeInput("reply", rules, constraints = emptyList()))

        // then — thinking off so reasoning can't truncate the verdict
        val params = apiScript.calls.single().params
        assertEquals(0, params.thinkingBudget)
        assertEquals(InvariantJudgePrompt.JUDGE_MAX_TOKENS, params.maxTokens)
    }
}

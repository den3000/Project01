package ru.den.writes.code.project01.shared.invariant

import ru.den.writes.code.project01.shared.memory.RuleEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvariantJudgePromptTest {

    //region buildJudgePrompt

    @Test
    fun `when buildJudgePrompt - then rules constraints and reply all included`() {
        // given
        val rules = listOf(RuleEntry("001", "Kotlin only, no Spring"))
        val constraints = listOf("no RxJava")
        val reply = "Use Spring Boot with RxJava"

        // when
        val actual = InvariantJudgePrompt.buildJudgePrompt(reply, rules, constraints)

        // then
        assertTrue(actual.contains("001"), "rule id missing")
        assertTrue(actual.contains("Kotlin only, no Spring"), "rule text missing")
        assertTrue(actual.contains("no RxJava"), "constraint missing")
        assertTrue(actual.contains(reply), "reply missing")
    }

    @Test
    fun `when buildJudgePrompt with no rules or constraints - then placeholders shown`() {
        // given / when
        val actual = InvariantJudgePrompt.buildJudgePrompt("hi", emptyList(), emptyList())

        // then
        assertTrue(actual.contains("(none)"), "empty placeholder missing")
    }
    //endregion

    //region parseVerdict — violations

    @Test
    fun `when parseVerdict sees a rule violation - then not passed with that ruleId`() {
        // given
        val text = """{"passed": false, "violations": [{"ruleId": "001", "explanation": "proposes Spring"}]}"""

        // when
        val actual = InvariantJudgePrompt.parseVerdict(text)

        // then
        assertFalse(actual.passed)
        assertEquals(1, actual.violations.size)
        assertEquals("001", actual.violations.single().ruleId)
        assertEquals("proposes Spring", actual.violations.single().explanation)
    }

    @Test
    fun `when parseVerdict violation has null ruleId - then kept with null id`() {
        // given — a constraint breach or a constraints↔rules conflict carries no rule id
        val text = """{"passed": false, "violations": [{"ruleId": null, "explanation": "uses RxJava"}]}"""

        // when
        val actual = InvariantJudgePrompt.parseVerdict(text)

        // then
        assertFalse(actual.passed)
        assertNull(actual.violations.single().ruleId)
        assertEquals("uses RxJava", actual.violations.single().explanation)
    }

    @Test
    fun `when parseVerdict has several violations - then all parsed in order`() {
        // given
        val text = """{"passed": false, "violations": [
            {"ruleId": "001", "explanation": "a"},
            {"ruleId": "002", "explanation": "b"}
        ]}"""

        // when
        val actual = InvariantJudgePrompt.parseVerdict(text)

        // then
        assertEquals(listOf("001", "002"), actual.violations.map { it.ruleId })
    }
    //endregion

    //region parseVerdict — clean / tolerant

    @Test
    fun `when parseVerdict sees empty violations - then passed`() {
        // given
        val text = """{"passed": true, "violations": []}"""

        // when
        val actual = InvariantJudgePrompt.parseVerdict(text)

        // then
        assertTrue(actual.passed)
        assertTrue(actual.violations.isEmpty())
    }

    @Test
    fun `when parseVerdict reply wrapped in json fence - then still parsed`() {
        // given
        val text = "```json\n{\"passed\": false, \"violations\": [{\"ruleId\": \"001\", \"explanation\": \"x\"}]}\n```"

        // when
        val actual = InvariantJudgePrompt.parseVerdict(text)

        // then
        assertFalse(actual.passed)
        assertEquals("001", actual.violations.single().ruleId)
    }

    @Test
    fun `when parseVerdict passed flag contradicts non-empty violations - then violations win`() {
        // given — model says passed:true but lists a breach; the flag is not trusted
        val text = """{"passed": true, "violations": [{"ruleId": "001", "explanation": "x"}]}"""

        // when
        val actual = InvariantJudgePrompt.parseVerdict(text)

        // then
        assertFalse(actual.passed)
    }

    @Test
    fun `when parseVerdict violation lacks an explanation - then it is dropped`() {
        // given — guards against empty hallucinated entries
        val text = """{"passed": false, "violations": [{"ruleId": "001"}, {"ruleId": "002", "explanation": "real"}]}"""

        // when
        val actual = InvariantJudgePrompt.parseVerdict(text)

        // then
        assertEquals(1, actual.violations.size)
        assertEquals("002", actual.violations.single().ruleId)
    }

    @Test
    fun `when parseVerdict gets null blank or non-json - then clean (fail-open)`() {
        // given / when / then — any unparseable input degrades to a clean pass
        assertTrue(InvariantJudgePrompt.parseVerdict(null).passed)
        assertTrue(InvariantJudgePrompt.parseVerdict("   ").passed)
        assertTrue(InvariantJudgePrompt.parseVerdict("Sure, looks fine to me!").passed)
        assertTrue(InvariantJudgePrompt.parseVerdict("[1,2,3]").passed)
    }
    //endregion

    //region parseVerdictOrNull — distinguishes fail-open from a clean verdict

    @Test
    fun `when parseVerdictOrNull gets prose - then null (fail-open signal)`() {
        // given / when / then — the judge babbled instead of returning JSON
        assertNull(InvariantJudgePrompt.parseVerdictOrNull("The reply writes code, which breaks the constraint."))
        assertNull(InvariantJudgePrompt.parseVerdictOrNull(null))
        assertNull(InvariantJudgePrompt.parseVerdictOrNull("   "))
    }

    @Test
    fun `when parseVerdictOrNull gets a valid object - then a verdict (not null)`() {
        // given
        val clean = """{"passed": true, "violations": []}"""
        val flagged = """{"passed": false, "violations": [{"ruleId": "001", "explanation": "x"}]}"""

        // when - then
        assertTrue(InvariantJudgePrompt.parseVerdictOrNull(clean)!!.passed)
        assertFalse(InvariantJudgePrompt.parseVerdictOrNull(flagged)!!.passed)
    }

    @Test
    fun `when parseVerdictOrNull gets an object without violations array - then clean verdict not null`() {
        // given — a valid object that just omits the array is a clean verdict, not a parse failure
        val obj = """{"passed": true}"""

        // when
        val actual = InvariantJudgePrompt.parseVerdictOrNull(obj)

        // then
        assertNotNull(actual)
        assertTrue(actual.passed)
    }
    //endregion
}

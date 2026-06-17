package ru.den.writes.code.project01.cliJvm.memory

import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.memory.MemoryLayer
import ru.den.writes.code.project01.shared.memory.ProfileData
import ru.den.writes.code.project01.shared.memory.RuleEntry
import ru.den.writes.code.project01.shared.memory.TaskNotes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryLayerTest {

    @Test
    fun `composePreamble returns empty when every input is empty`() {
        assertEquals(emptyList(), MemoryLayer.composePreamble(null, emptyList(), null))
        assertEquals(emptyList(), MemoryLayer.composePreamble(ProfileData(), emptyList(), null))
    }

    @Test
    fun `composePreamble emits exactly one USER then one ASSISTANT pair when any layer is filled`() {
        val msgs = MemoryLayer.composePreamble(
            profile = ProfileData(freeText = "I write Kotlin"),
            rules = emptyList(),
            task = null,
        )
        assertEquals(2, msgs.size)
        assertEquals(Role.USER, msgs[0].role)
        assertEquals(Role.ASSISTANT, msgs[1].role)
        assertEquals(MemoryLayer.PREAMBLE_ACK, msgs[1].text)
        assertTrue(msgs[0].text.startsWith(MemoryLayer.PROFILE_HEADING))
    }

    @Test
    fun `composePreamble concatenates filled sections in fixed order`() {
        val msgs = MemoryLayer.composePreamble(
            profile = ProfileData(freeText = "P"),
            rules = listOf(RuleEntry("001", "R1"), RuleEntry("002", "R2")),
            task = TaskNotes("t", goal = "G", stage = "S", notes = listOf("N1")),
        )
        val body = msgs[0].text
        val pi = body.indexOf(MemoryLayer.PROFILE_HEADING)
        val ri = body.indexOf(MemoryLayer.RULES_HEADING)
        val ti = body.indexOf(MemoryLayer.TASK_HEADING)
        assertTrue(pi in 0 until ri && ri in 0 until ti, "expected profile<rules<task order, got $pi/$ri/$ti")
        assertTrue(body.contains("- R1"))
        assertTrue(body.contains("- R2"))
        assertTrue(body.contains("Goal: G"))
        assertTrue(body.contains("Stage: S"))
        assertTrue(body.contains("- N1"))
    }

    @Test
    fun `composeSystem emits one SYSTEM message per non-empty section`() {
        val msgs = MemoryLayer.composeSystem(
            profile = ProfileData(freeText = "P"),
            rules = listOf(RuleEntry("001", "R1")),
            task = TaskNotes("t", goal = "G"),
        )
        assertEquals(3, msgs.size)
        assertTrue(msgs.all { it.role == Role.SYSTEM })
        assertTrue(msgs[0].text.startsWith(MemoryLayer.PROFILE_HEADING))
        assertTrue(msgs[1].text.startsWith(MemoryLayer.RULES_HEADING))
        assertTrue(msgs[2].text.startsWith(MemoryLayer.TASK_HEADING))
    }

    @Test
    fun `composeSystem returns empty when every input is empty`() {
        assertEquals(emptyList(), MemoryLayer.composeSystem(null, emptyList(), null))
    }

    @Test
    fun `composeSystem skips sections that exist but have no body`() {
        val msgs = MemoryLayer.composeSystem(
            profile = null,
            rules = emptyList(),
            task = TaskNotes("t"),
        )
        assertEquals(emptyList(), msgs)
    }

    @Test
    fun `rule bodies have newlines flattened so the bulleted list stays single-line`() {
        val msgs = MemoryLayer.composeSystem(
            profile = null,
            rules = listOf(RuleEntry("001", "Line 1\nLine 2")),
            task = null,
        )
        val text = msgs.single().text
        assertEquals("${MemoryLayer.RULES_HEADING}\n- Line 1 Line 2", text)
    }

    @Test
    fun `structured profile renders sub-sections under one Profile heading`() {
        val msgs = MemoryLayer.composePreamble(
            profile = ProfileData(
                style = listOf("кратко", "русский"),
                format = listOf("code-first"),
                constraints = listOf("Kotlin"),
                context = listOf("Android dev"),
            ),
            rules = emptyList(),
            task = null,
        )
        val body = msgs.single { it.role == Role.USER }.text
        assertTrue(body.startsWith(MemoryLayer.PROFILE_HEADING))
        assertTrue(body.contains("Style:\n- кратко\n- русский"))
        assertTrue(body.contains("Format:\n- code-first"))
        assertTrue(body.contains("Constraints:\n- Kotlin"))
        assertTrue(body.contains("Context:\n- Android dev"))
        // Sub-sections come in the fixed ProfileSection order.
        val si = body.indexOf("Style:")
        val fi = body.indexOf("Format:")
        val ki = body.indexOf("Constraints:")
        val ci = body.indexOf("Context:")
        assertTrue(si < fi && fi < ki && ki < ci, "expected style<format<constraints<context, got $si/$fi/$ki/$ci")
    }

    @Test
    fun `composeSystem keeps Profile as one SYSTEM message even with multiple sub-sections`() {
        val msgs = MemoryLayer.composeSystem(
            profile = ProfileData(
                style = listOf("a"),
                format = listOf("b"),
                constraints = listOf("c"),
                context = listOf("d"),
            ),
            rules = emptyList(),
            task = null,
        )
        assertEquals(1, msgs.size, "Profile must be ONE Role.SYSTEM message, not one per sub-section")
        assertEquals(Role.SYSTEM, msgs[0].role)
        assertTrue(msgs[0].text.contains("Style:\n- a"))
        assertTrue(msgs[0].text.contains("Context:\n- d"))
    }

    @Test
    fun `free-text-only profile renders byte-identical to the unstructured wire shape`() {
        val msgs = MemoryLayer.composeSystem(
            profile = ProfileData(freeText = "Я пишу только на Kotlin"),
            rules = emptyList(),
            task = null,
        )
        // The exact unstructured shape: `[Profile]\n<text>`.
        assertEquals("${MemoryLayer.PROFILE_HEADING}\nЯ пишу только на Kotlin", msgs.single().text)
    }
}

package ru.den.writes.code.project01.cliJvm.plain

import ru.den.writes.code.project01.cliJvm.AgentRef
import kotlin.test.Test
import kotlin.test.assertEquals

/** The assistant reply line and its optional `[[AGENT:]]` tag on stdout. */
class AssistantPlainViewTest {

    @Test
    fun `when no agent - then stdout is just the reply`() {
        assertEquals(listOf("the reply"), AssistantPlainView("the reply", agent = null).stdout())
    }

    @Test
    fun `when an agent with a profile - then the tag prefixes the reply`() {
        assertEquals(
            listOf("[[AGENT: planner:gemini-2.5-flash]]", "the reply"),
            AssistantPlainView("the reply", AgentRef("planner", "gemini-2.5-flash")).stdout(),
        )
    }

    @Test
    fun `when an agent without a profile - then the tag shows default`() {
        assertEquals(
            listOf("[[AGENT: default:m]]", "x"),
            AssistantPlainView("x", AgentRef(profileName = null, modelId = "m")).stdout(),
        )
    }
}

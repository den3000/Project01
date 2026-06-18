package ru.den.writes.code.project01.shared.agent

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.shared.llm.FakeLlmApi
import ru.den.writes.code.project01.shared.llm.GenerationParams
import ru.den.writes.code.project01.shared.llm.LlmResult
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.memory.TaskStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AgentResponderTest {

    private fun responder(api: FakeLlmApi, params: GenerationParams = GenerationParams()) =
        AgentResponder(AgentConfig(llmApi = api, params = params))

    @Test
    fun `when respond - then wire is memoryLayer plus baseContext plus userTurn in order`() = runTest {
        // given
        val api = FakeLlmApi().apply { queueText("ok") }
        val memoryLayer = listOf(Message(Role.SYSTEM, "profile"))
        val baseContext = listOf(
            Message(Role.USER, "earlier"),
            Message(Role.ASSISTANT, "reply"),
        )
        val userTurn = Message(Role.USER, "now")

        // when
        responder(api).respond(baseContext = baseContext, memoryLayer = memoryLayer, userTurn = userTurn)

        // then
        assertEquals(memoryLayer + baseContext + userTurn, api.calls.single().messages)
    }

    @Test
    fun `when reply carries a legal stage marker - then proposedStage is parsed`() = runTest {
        // given
        val api = FakeLlmApi().apply { queueText("plan ready [[stage:execution]]") }

        // when
        val outcome = responder(api).respond(emptyList(), emptyList(), Message(Role.USER, "go"))

        // then
        assertEquals(TaskStage.EXECUTION, outcome.proposedStage)
    }

    @Test
    fun `when reply has no stage marker - then proposedStage is null`() = runTest {
        // given
        val api = FakeLlmApi().apply { queueText("just a normal reply") }

        // when
        val outcome = responder(api).respond(emptyList(), emptyList(), Message(Role.USER, "go"))

        // then
        assertNull(outcome.proposedStage)
    }

    @Test
    fun `when result is an error - then outcome passes it through with null proposedStage`() = runTest {
        // given
        val error = LlmResult(text = null, error = "boom")
        val api = FakeLlmApi().apply { queue(error) }

        // when
        val outcome = responder(api).respond(emptyList(), emptyList(), Message(Role.USER, "go"))

        // then
        assertSame(error, outcome.result)
        assertNull(outcome.proposedStage)
    }

    @Test
    fun `when params are configured - then they are forwarded verbatim`() = runTest {
        // given
        val api = FakeLlmApi().apply { queueText("ok") }
        val params = GenerationParams(maxTokens = 256, temperature = 0.2)

        // when
        responder(api, params).respond(emptyList(), emptyList(), Message(Role.USER, "go"))

        // then
        assertEquals(params, api.calls.single().params)
    }

    @Test
    fun `when config carries a profileName - then responder ignores it and wires unchanged`() = runTest {
        // given
        // profileName is host-interpreted (the loop composes the layer for it);
        // the responder must not read it — the wire stays memoryLayer + userTurn.
        val api = FakeLlmApi().apply { queueText("ok") }
        val responder = AgentResponder(
            AgentConfig(llmApi = api, params = GenerationParams(), profileName = "planner"),
        )
        val memoryLayer = listOf(Message(Role.SYSTEM, "p"))
        val userTurn = Message(Role.USER, "now")

        // when
        responder.respond(baseContext = emptyList(), memoryLayer = memoryLayer, userTurn = userTurn)

        // then
        assertEquals(memoryLayer + userTurn, api.calls.single().messages)
    }
}

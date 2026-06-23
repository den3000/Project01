package ru.den.writes.code.project01.shared.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.den.writes.code.project01.shared.llm.FakeLlmApi
import ru.den.writes.code.project01.shared.llm.GenerationParams
import ru.den.writes.code.project01.shared.llm.LlmResult
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.llm.ToolCall
import ru.den.writes.code.project01.shared.llm.ToolExecutor
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

    @Test
    fun `when model returns a tool call - then executor runs it and the result is fed back`() = runTest {
        // given
        val args = buildJsonObject { put("city", "Paris") }
        val api = FakeLlmApi().apply {
            queue(LlmResult(text = null, toolCalls = listOf(ToolCall("current_weather", args))))
            queueText("It is sunny in Paris.")
        }
        val executor = RecordingExecutor("Paris: 18C")
        val responder = AgentResponder(AgentConfig(llmApi = api, params = GenerationParams(), toolExecutor = executor))

        // when
        val outcome = responder.respond(emptyList(), emptyList(), Message(Role.USER, "weather in Paris?"))

        // then
        assertEquals(listOf(ToolCall("current_weather", args)), executor.calls)
        assertEquals("It is sunny in Paris.", outcome.result.text)
        assertEquals(
            listOf(ExecutedToolCall(ToolCall("current_weather", args), "Paris: 18C")),
            outcome.executedToolCalls,
        )
        // The second send carried the model's call turn + the tool result back.
        assertEquals(2, api.calls.size)
        val resent = api.calls[1].messages
        assertEquals(listOf(ToolCall("current_weather", args)), resent[resent.size - 2].toolCalls)
        assertEquals("current_weather", resent.last().toolResultFor)
        assertEquals("Paris: 18C", resent.last().text)
    }

    @Test
    fun `when no executor configured - then a tool-call result passes straight through`() = runTest {
        // given — without an executor the responder can't run tools; it returns the call result as-is.
        val args = buildJsonObject { put("city", "Paris") }
        val callResult = LlmResult(text = null, toolCalls = listOf(ToolCall("current_weather", args)))
        val api = FakeLlmApi().apply { queue(callResult) }

        // when
        val outcome = responder(api).respond(emptyList(), emptyList(), Message(Role.USER, "go"))

        // then
        assertEquals(1, api.calls.size)
        assertSame(callResult, outcome.result)
        assertEquals(emptyList<ExecutedToolCall>(), outcome.executedToolCalls)
    }

    @Test
    fun `when model answers without tool calls - then executor is never touched`() = runTest {
        // given
        val api = FakeLlmApi().apply { queueText("plain answer") }
        val executor = RecordingExecutor("unused")
        val responder = AgentResponder(AgentConfig(llmApi = api, params = GenerationParams(), toolExecutor = executor))

        // when
        val outcome = responder.respond(emptyList(), emptyList(), Message(Role.USER, "hi"))

        // then
        assertEquals(1, api.calls.size)
        assertEquals(emptyList<ToolCall>(), executor.calls)
        assertEquals("plain answer", outcome.result.text)
        assertEquals(emptyList<ExecutedToolCall>(), outcome.executedToolCalls)
    }

    private class RecordingExecutor(private val output: String) : ToolExecutor {
        val calls = mutableListOf<ToolCall>()
        override suspend fun execute(call: ToolCall): String {
            calls += call
            return output
        }
    }
}

package ru.den.writes.code.agenticHub.features.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.llm.FakeLlmScript
import ru.den.writes.code.agenticHub.features.llm.GenerationParams
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.LlmResult
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.llm.ToolCall
import ru.den.writes.code.agenticHub.features.llm.ToolExecutor
import ru.den.writes.code.agenticHub.features.llm.di.llmTestModule
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AgentResponderTest {

    /** The scripted [LlmApi] resolved from the graph, driven by [script]. */
    private fun scriptedApi(script: FakeLlmScript): LlmApi =
        koinApplication { modules(llmTestModule) }.koin.get { parametersOf(script) }

    private fun responder(api: LlmApi, params: GenerationParams = GenerationParams()) =
        AgentResponder(AgentConfig(llmApi = api, params = params))

    @Test
    fun `when respond - then wire is memoryLayer plus baseContext plus userTurn in order`() = runTest {
        // given
        val script = FakeLlmScript().apply { queueText("ok") }
        val api = scriptedApi(script)
        val memoryLayer = listOf(Message(Role.SYSTEM, "profile"))
        val baseContext = listOf(
            Message(Role.USER, "earlier"),
            Message(Role.ASSISTANT, "reply"),
        )
        val userTurn = Message(Role.USER, "now")

        // when
        responder(api).respond(baseContext = baseContext, memoryLayer = memoryLayer, userTurn = userTurn)

        // then
        assertEquals(memoryLayer + baseContext + userTurn, script.calls.single().messages)
    }

    @Test
    fun `when reply carries a legal stage marker - then proposedStage is parsed`() = runTest {
        // given
        val script = FakeLlmScript().apply { queueText("plan ready [[stage:execution]]") }
        val api = scriptedApi(script)

        // when
        val outcome = responder(api).respond(emptyList(), emptyList(), Message(Role.USER, "go"))

        // then
        assertEquals(TaskStage.EXECUTION, outcome.proposedStage)
    }

    @Test
    fun `when reply has no stage marker - then proposedStage is null`() = runTest {
        // given
        val script = FakeLlmScript().apply { queueText("just a normal reply") }
        val api = scriptedApi(script)

        // when
        val outcome = responder(api).respond(emptyList(), emptyList(), Message(Role.USER, "go"))

        // then
        assertNull(outcome.proposedStage)
    }

    @Test
    fun `when result is an error - then outcome passes it through with null proposedStage`() = runTest {
        // given
        val error = LlmResult(text = null, error = "boom")
        val script = FakeLlmScript().apply { queue(error) }
        val api = scriptedApi(script)

        // when
        val outcome = responder(api).respond(emptyList(), emptyList(), Message(Role.USER, "go"))

        // then
        assertSame(error, outcome.result)
        assertNull(outcome.proposedStage)
    }

    @Test
    fun `when params are configured - then they are forwarded verbatim`() = runTest {
        // given
        val script = FakeLlmScript().apply { queueText("ok") }
        val api = scriptedApi(script)
        val params = GenerationParams(maxTokens = 256, temperature = 0.2)

        // when
        responder(api, params).respond(emptyList(), emptyList(), Message(Role.USER, "go"))

        // then
        assertEquals(params, script.calls.single().params)
    }

    @Test
    fun `when config carries a profileName - then responder ignores it and wires unchanged`() = runTest {
        // given
        // profileName is host-interpreted (the loop composes the layer for it);
        // the responder must not read it — the wire stays memoryLayer + userTurn.
        val script = FakeLlmScript().apply { queueText("ok") }
        val api = scriptedApi(script)
        val responder = AgentResponder(
            AgentConfig(llmApi = api, params = GenerationParams(), profileName = "planner"),
        )
        val memoryLayer = listOf(Message(Role.SYSTEM, "p"))
        val userTurn = Message(Role.USER, "now")

        // when
        responder.respond(baseContext = emptyList(), memoryLayer = memoryLayer, userTurn = userTurn)

        // then
        assertEquals(memoryLayer + userTurn, script.calls.single().messages)
    }

    @Test
    fun `when model returns a tool call - then executor runs it and the result is fed back`() = runTest {
        // given
        val args = buildJsonObject { put("city", "Paris") }
        val script = FakeLlmScript().apply {
            queue(LlmResult(text = null, toolCalls = listOf(ToolCall("current_weather", args))))
            queueText("It is sunny in Paris.")
        }
        val api = scriptedApi(script)
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
        assertEquals(2, script.calls.size)
        val resent = script.calls[1].messages
        assertEquals(listOf(ToolCall("current_weather", args)), resent[resent.size - 2].toolCalls)
        assertEquals("current_weather", resent.last().toolResultFor)
        assertEquals("Paris: 18C", resent.last().text)
    }

    @Test
    fun `when no executor configured - then a tool-call result passes straight through`() = runTest {
        // given — without an executor the responder can't run tools; it returns the call result as-is.
        val args = buildJsonObject { put("city", "Paris") }
        val callResult = LlmResult(text = null, toolCalls = listOf(ToolCall("current_weather", args)))
        val script = FakeLlmScript().apply { queue(callResult) }
        val api = scriptedApi(script)

        // when
        val outcome = responder(api).respond(emptyList(), emptyList(), Message(Role.USER, "go"))

        // then
        assertEquals(1, script.calls.size)
        assertSame(callResult, outcome.result)
        assertEquals(emptyList<ExecutedToolCall>(), outcome.executedToolCalls)
    }

    @Test
    fun `when model answers without tool calls - then executor is never touched`() = runTest {
        // given
        val script = FakeLlmScript().apply { queueText("plain answer") }
        val api = scriptedApi(script)
        val executor = RecordingExecutor("unused")
        val responder = AgentResponder(AgentConfig(llmApi = api, params = GenerationParams(), toolExecutor = executor))

        // when
        val outcome = responder.respond(emptyList(), emptyList(), Message(Role.USER, "hi"))

        // then
        assertEquals(1, script.calls.size)
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

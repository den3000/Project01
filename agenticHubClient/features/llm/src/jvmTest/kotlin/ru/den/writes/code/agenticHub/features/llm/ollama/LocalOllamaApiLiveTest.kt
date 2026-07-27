package ru.den.writes.code.agenticHub.features.llm.ollama

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.llm.GenerationParams
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.LlmResult
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.llm.ToolDefinition
import ru.den.writes.code.agenticHub.features.llm.di.llmModule
import ru.den.writes.code.agenticHub.features.llm.liveChatModel
import ru.den.writes.code.agenticHub.features.llm.liveOllamaTest
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Opt-in live test (see LIVE_TESTS.md): the central gate excludes `*LiveTest` unless
// `-PliveTests`. Reachability skip lives in liveOllamaTest; a non-skipped run needs the
// generative tag pulled (`ollama pull <tag>`, default gemma4:26b, override via
// -Dollama.chat.model=<tag>). Resolves LlmApi from the real production graph (llmModule
// over networkModule's HttpClient).
class LocalOllamaApiLiveTest {

    private val koin = koinApplication { modules(llmModule, networkModule) }.koin

    @Test
    fun `when a plain question is sent - then a non-empty answer comes back`() = liveOllamaTest(koin) {
        // given
        val api = localOllamaApi()
        val messages = listOf(Message(Role.USER, "Reply with a single word: what colour is the sky on a clear day?"))
        val params = GenerationParams(temperature = 0.0, maxTokens = 32, thinkingBudget = 0)

        // when
        val actual = api.send(messages, params)

        // then
        assertNull(actual.error, "expected success, got error: ${actual.error}")
        assertTrue(!actual.text.isNullOrBlank(), "expected non-empty text")
        println("[ollama chat] model=${liveChatModel().id} → \"${actual.text?.trim()}\" (${actual.usage?.totalTokens} tok)")
    }

    @Test
    fun `when a system instruction is sent - then it is honoured`() = liveOllamaTest(koin) {
        // given
        val api = localOllamaApi()
        val messages = listOf(
            Message(Role.SYSTEM, "You always answer in exactly one uppercase word."),
            Message(Role.USER, "Name a primary colour."),
        )
        val params = GenerationParams(temperature = 0.0, maxTokens = 16, thinkingBudget = 0)

        // when
        val actual = api.send(messages, params)

        // then
        assertNull(actual.error, "expected success, got error: ${actual.error}")
        assertTrue(!actual.text.isNullOrBlank(), "expected non-empty text")
        println("[ollama chat+system] → \"${actual.text?.trim()}\"")
    }

    @Test
    fun `when a tool is declared - then the model asks to call it`() = liveOllamaTest(koin) {
        // given
        val api = localOllamaApi()
        val messages = listOf(Message(Role.USER, "What is the weather in Paris? Use the tool."))
        val params = toolParams()

        // when
        val actual = api.send(messages, params)

        // then
        assumeToolsCapable(actual)
        assertNull(actual.error, "expected success, got error: ${actual.error}")
        val call = actual.toolCalls.singleOrNull()
        assertEquals(WEATHER_TOOL, call?.name, "expected a call to $WEATHER_TOOL, got: ${actual.toolCalls}")
        println("[ollama tools] ${call?.name}(${call?.arguments})")
    }

    /**
     * The full round trip — the leg that only a real server can check: our replay of the
     * call (`role:"assistant"` + `tool_calls`) and of the result (`role:"tool"` +
     * `tool_name`) has to be accepted as a valid continuation of the exchange.
     */
    @Test
    fun `when a tool result is fed back - then the model answers from it`() = liveOllamaTest(koin) {
        // given
        val api = localOllamaApi()
        val question = Message(Role.USER, "What is the weather in Paris? Use the tool.")
        val first = api.send(listOf(question), toolParams())
        assumeToolsCapable(first)
        val call = first.toolCalls.firstOrNull()
        assumeTrue("model did not call the tool — nothing to feed back", call != null)
        val wire = listOf(
            question,
            Message(Role.ASSISTANT, first.text.orEmpty(), toolCalls = first.toolCalls),
            Message(Role.USER, "21 degrees and sunny", toolResultFor = call!!.name),
        )

        // when
        val actual = api.send(wire, toolParams())

        // then
        assertNull(actual.error, "expected success, got error: ${actual.error}")
        assertTrue(actual.text?.contains("21") == true, "expected the answer to use the result, was: ${actual.text}")
        println("[ollama tools round-trip] → \"${actual.text?.trim()}\"")
    }

    /**
     * Not every locally pulled tag can call tools, and the default one is a plain chat
     * model — Ollama answers such a request with an explicit error. Skipping (rather than
     * failing) keeps the run honest on a machine that simply has no tools-capable tag,
     * the same way the reachability check does for a server that is down.
     */
    private fun assumeToolsCapable(result: LlmResult) {
        val unsupported = result.error?.contains("does not support tools", ignoreCase = true) == true
        assumeTrue("model ${liveChatModel().id} does not support tools — skipping", !unsupported)
    }

    private fun toolParams(): GenerationParams =
        GenerationParams(temperature = 0.0, maxTokens = 256, thinkingBudget = 0, tools = listOf(weatherTool()))

    private fun weatherTool(): ToolDefinition =
        ToolDefinition(
            name = WEATHER_TOOL,
            description = "Get the current weather in a city",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("city", buildJsonObject { put("type", "string") }) })
                put("required", buildJsonArray { add("city") })
            },
        )

    private fun localOllamaApi(): LlmApi =
        koin.get { parametersOf(ModelProvider.LocalOllama(model = liveChatModel())) }

    private companion object {
        const val WEATHER_TOOL = "current_weather"
    }
}

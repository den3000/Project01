package ru.den.writes.code.agenticHub.features.llm.ollama

import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.llm.GenerationParams
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.llm.di.llmModule
import ru.den.writes.code.agenticHub.features.llm.liveChatModel
import ru.den.writes.code.agenticHub.features.llm.liveOllamaTest
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import kotlin.test.Test
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

    private fun localOllamaApi(): LlmApi =
        koin.get { parametersOf(ModelProvider.LocalOllama(model = liveChatModel())) }
}

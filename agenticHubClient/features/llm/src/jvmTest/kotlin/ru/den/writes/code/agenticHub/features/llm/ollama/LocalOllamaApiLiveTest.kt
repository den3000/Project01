package ru.den.writes.code.agenticHub.features.llm.ollama

import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.llm.GenerationParams
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.llm.liveChatModel
import ru.den.writes.code.agenticHub.features.llm.liveOllamaTest
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Opt-in live test (see LIVE_TESTS.md): the central gate excludes `*LiveTest` unless
// `-PliveTests`. Reachability skip lives in liveOllamaTest; a non-skipped run needs the
// generative tag pulled (`ollama pull <tag>`, default gemma4:26b, override via
// -Dollama.chat.model=<tag>). Uses the real networkModule HttpClient against a live model.
class LocalOllamaApiLiveTest {

    // One koin per test method (fresh JUnit4 instance per @Test → fresh graph).
    private val koin = koinApplication { modules(networkModule) }.koin

    @Test
    fun `when a plain question is sent - then a non-empty answer comes back`() = liveOllamaTest(koin) {
        // given
        val api = LocalOllamaApi(httpClient = koin.get(), model = liveChatModel())

        // when
        val result = api.send(
            messages = listOf(Message(Role.USER, "Reply with a single word: what colour is the sky on a clear day?")),
            params = GenerationParams(temperature = 0.0, maxTokens = 32),
        )

        // then
        assertNull(result.error, "expected success, got error: ${result.error}")
        assertTrue(!result.text.isNullOrBlank(), "expected non-empty text")
        println("[ollama chat] model=${liveChatModel().id} → \"${result.text?.trim()}\" (${result.usage?.totalTokens} tok)")
    }

    @Test
    fun `when a system instruction is sent - then it is honoured`() = liveOllamaTest(koin) {
        // given
        val api = LocalOllamaApi(httpClient = koin.get(), model = liveChatModel())

        // when — SYSTEM must route into Ollama's combined system message
        val result = api.send(
            messages = listOf(
                Message(Role.SYSTEM, "You always answer in exactly one uppercase word."),
                Message(Role.USER, "Name a primary colour."),
            ),
            params = GenerationParams(temperature = 0.0, maxTokens = 16),
        )

        // then
        assertNull(result.error, "expected success, got error: ${result.error}")
        assertTrue(!result.text.isNullOrBlank(), "expected non-empty text")
        println("[ollama chat+system] → \"${result.text?.trim()}\"")
    }
}

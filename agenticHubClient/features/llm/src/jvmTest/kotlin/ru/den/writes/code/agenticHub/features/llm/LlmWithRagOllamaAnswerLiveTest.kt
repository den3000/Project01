package ru.den.writes.code.agenticHub.features.llm

import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.llm.di.llmModule
import ru.den.writes.code.agenticHub.features.rag.di.ragModule
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import kotlin.test.Test

// Opt-in live test (see LIVE_TESTS.md): excluded unless `-PliveTests`. Needs a live Ollama with
// BOTH a generative tag (default gemma4:26b, -Dollama.chat.model=<tag>) and the embed model
// (`ollama pull nomic-embed-text`). The "first RAG query" comparison with a LOCAL generative model;
// the shared flow (index → retrieve → answer with/without) lives in assertRagComparison. The Gemini
// variant is LlmWithRagGeminiAnswerLiveTest.
class LlmWithRagOllamaAnswerLiveTest {

    private val koin = koinApplication { modules(llmModule, ragModule, networkModule) }.koin

    @Test
    fun `when control questions run through Ollama with the index vs without - then both modes answer and RAG is grounded`() =
        liveOllamaTest(koin) {
            // given
            val chatApi = koin.get<LlmApi> { parametersOf(ModelProvider.LocalOllama(model = liveChatModel())) }

            // when / then
            assertRagComparison(koin, chatApi, label = "ollama ${liveChatModel().id}")
        }
}

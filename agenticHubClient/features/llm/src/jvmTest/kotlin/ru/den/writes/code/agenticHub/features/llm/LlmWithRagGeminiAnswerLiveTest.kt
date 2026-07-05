package ru.den.writes.code.agenticHub.features.llm

import org.junit.Assume.assumeTrue
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.BuildKonfig
import ru.den.writes.code.agenticHub.features.llm.di.llmModule
import ru.den.writes.code.agenticHub.features.llm.gemini.GeminiModel
import ru.den.writes.code.agenticHub.features.rag.di.ragModule
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import kotlin.test.Test

// Opt-in live test (see LIVE_TESTS.md): excluded unless `-PliveTests`. Same RAG comparison as
// LlmWithRagOllamaAnswerLiveTest, but generation goes to the REAL Gemini API — so a non-skipped
// run BURNS TOKENS and needs GEMINI_API_KEY (via BuildKonfig / local.properties). Retrieval still
// embeds through local Ollama, so it also needs Ollama + `ollama pull nomic-embed-text`. Skips when
// the key is unset or Ollama is down. The key value is never printed.
class LlmWithRagGeminiAnswerLiveTest {

    private val koin = koinApplication { modules(llmModule, ragModule, networkModule) }.koin

    @Test
    fun `when control questions run through Gemini with the index vs without - then both modes answer and RAG is grounded`() {
        assumeTrue("GEMINI_API_KEY not set — skipping Gemini live test", BuildKonfig.GEMINI_API_KEY.isNotBlank())
        liveOllamaTest(koin) {
            // given
            val chatApi = koin.get<LlmApi> {
                parametersOf(ModelProvider.Gemini(model = GeminiModel.Default, apiKey = BuildKonfig.GEMINI_API_KEY))
            }

            // when / then
            assertRagComparison(koin, chatApi, label = "gemini ${GeminiModel.Default.id}")
        }
    }
}

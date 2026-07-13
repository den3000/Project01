package ru.den.writes.code.agenticHub.features.llm

import ru.den.writes.code.agenticHub.features.llm.ollama.OllamaModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LlmFactoriesTest {

    @Test
    fun `when buildModelProvider is given ollama - then it resolves without an api key`() {
        // given
        val provider = PROVIDER_OLLAMA

        // when
        val actual = buildModelProviderWithBlankKeys(provider, modelRaw = null)

        // then
        assertIs<ModelProvider.LocalOllama>(actual)
        assertEquals(OllamaModel.Default.id, actual.modelId)
        assertEquals("http://localhost:11434/api/chat", actual.endpoint)
    }

    @Test
    fun `when buildModelProvider is given an explicit ollama tag - then it is forwarded verbatim`() {
        // given
        val tag = "gemma3:27b"

        // when
        val actual = buildModelProviderWithBlankKeys(PROVIDER_OLLAMA, modelRaw = tag)

        // then
        assertEquals(tag, actual.modelId)
    }

    @Test
    fun `when buildModelProvider ollama is given a host - then the endpoint targets the remote server`() {
        // given
        val host = "https://ollama.example.amvera.io"

        // when
        val actual = buildModelProviderWithBlankKeys(PROVIDER_OLLAMA, modelRaw = null, ollamaBaseUrl = host)

        // then
        assertIs<ModelProvider.LocalOllama>(actual)
        assertEquals("$host/api/chat", actual.endpoint)
    }

    @Test
    fun `when buildModelProvider ollama is given no host - then the endpoint stays local`() {
        // given / when
        val actual = buildModelProviderWithBlankKeys(PROVIDER_OLLAMA, modelRaw = null, ollamaBaseUrl = null)

        // then
        assertEquals("http://localhost:11434/api/chat", actual.endpoint)
    }

    private fun buildModelProviderWithBlankKeys(
        provider: String,
        modelRaw: String?,
        ollamaBaseUrl: String? = null,
    ): ModelProvider =
        buildModelProvider(
            providerRaw = provider,
            modelRaw = modelRaw,
            geminiApiKey = "",
            openRouterApiKey = "",
            huggingFaceApiKey = "",
            ollamaBaseUrl = ollamaBaseUrl,
        )
}

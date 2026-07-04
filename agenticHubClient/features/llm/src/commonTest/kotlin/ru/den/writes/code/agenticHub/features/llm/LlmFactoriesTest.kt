package ru.den.writes.code.agenticHub.features.llm

import ru.den.writes.code.agenticHub.features.llm.ollama.OllamaModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmFactoriesTest {

    @Test
    fun `buildModelProvider resolves ollama without any api key`() {
        val mp = buildModelProvider(
            providerRaw = PROVIDER_OLLAMA,
            modelRaw = null,
            geminiApiKey = "",
            openRouterApiKey = "",
            huggingFaceApiKey = "",
        )
        assertTrue(mp is ModelProvider.LocalOllama)
        assertEquals(OllamaModel.Default.id, mp.modelId)
        assertEquals("http://localhost:11434/api/chat", mp.endpoint)
    }

    @Test
    fun `buildModelProvider forwards an explicit ollama tag verbatim`() {
        val mp = buildModelProvider(
            providerRaw = PROVIDER_OLLAMA,
            modelRaw = "gemma3:27b",
            geminiApiKey = "",
            openRouterApiKey = "",
            huggingFaceApiKey = "",
        )
        assertEquals("gemma3:27b", mp.modelId)
    }
}

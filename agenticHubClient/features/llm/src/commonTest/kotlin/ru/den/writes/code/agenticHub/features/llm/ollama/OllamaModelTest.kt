package ru.den.writes.code.agenticHub.features.llm.ollama

import kotlin.test.Test
import kotlin.test.assertEquals

class OllamaModelTest {

    @Test
    fun `fromId resolves every Known tag back to its enum entry`() {
        OllamaModel.Known.entries.forEach { known ->
            assertEquals(known, OllamaModel.fromId(known.id))
        }
    }

    @Test
    fun `fromId falls back to Custom for an unpulled tag`() {
        assertEquals(OllamaModel.Custom("mistral-small:24b"), OllamaModel.fromId("mistral-small:24b"))
    }

    @Test
    fun `Default is a Known generative tag`() {
        assertEquals(OllamaModel.Known.Gemma4_26b, OllamaModel.Default)
    }
}

package ru.den.writes.code.agenticHub.features.llm.ollama

import kotlin.test.Test
import kotlin.test.assertEquals

class OllamaModelTest {

    @Test
    fun `when fromId is given a Known tag - then it resolves to that enum entry`() {
        OllamaModel.Known.entries.forEach { known ->
            // when
            val actual = OllamaModel.fromId(known.id)

            // then
            assertEquals(known, actual, "tag ${known.id} should resolve to $known")
        }
    }

    @Test
    fun `when fromId is given an unpulled tag - then it falls back to Custom`() {
        // given
        val tag = "mistral-small:24b"

        // when
        val actual = OllamaModel.fromId(tag)

        // then
        assertEquals(OllamaModel.Custom(tag), actual)
    }

    @Test
    fun `when Default is read - then it is a Known generative tag`() {
        // when
        val actual = OllamaModel.Default

        // then
        assertEquals(OllamaModel.Known.Gemma4_26b, actual)
    }
}

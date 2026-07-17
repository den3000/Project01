package ru.den.writes.code.agenticHub.cliJvm.di

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Key precedence: the runtime environment beats the value BuildKonfig baked in at build
 * time. This is what lets a binary be built with no secrets and handed them per run.
 */
class ResolveKeyTest {

    @Test
    fun `when the env var is set - then it wins over the baked key`() {
        // given
        val env = mapOf("GEMINI_API_KEY" to "from-env")

        // when
        val key = resolveKey("GEMINI_API_KEY", baked = "from-buildkonfig", env = env::get)

        // then
        assertEquals("from-env", key)
    }

    @Test
    fun `when the env var is absent - then the baked key is used`() {
        // given — a developer machine: no env, local.properties compiled in
        val env = emptyMap<String, String>()

        // when
        val key = resolveKey("GEMINI_API_KEY", baked = "from-buildkonfig", env = env::get)

        // then
        assertEquals("from-buildkonfig", key)
    }

    @Test
    fun `when the env var is blank - then it counts as absent`() {
        // given — an unset CI secret expands to an empty string, not a missing var
        val env = mapOf("GEMINI_API_KEY" to "   ")

        // when
        val key = resolveKey("GEMINI_API_KEY", baked = "from-buildkonfig", env = env::get)

        // then
        assertEquals("from-buildkonfig", key)
    }

    @Test
    fun `when neither is set - then the key is empty`() {
        // given — a secretless build with no env: the provider reports a missing key later
        val env = emptyMap<String, String>()

        // when
        val key = resolveKey("GEMINI_API_KEY", baked = "", env = env::get)

        // then
        assertEquals("", key)
    }

    @Test
    fun `when resolving - then each key reads its own variable`() {
        // given
        val env = mapOf("GEMINI_API_KEY" to "g", "OPENROUTER_API_KEY" to "o")

        // when - then
        assertEquals("g", resolveKey("GEMINI_API_KEY", baked = "", env = env::get))
        assertEquals("o", resolveKey("OPENROUTER_API_KEY", baked = "", env = env::get))
        assertEquals("baked-hf", resolveKey("HUGGINGFACE_API_KEY", baked = "baked-hf", env = env::get))
    }
}

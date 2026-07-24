package ru.den.writes.code.agenticHub.platform.config

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
        val env = mapOf(ApiKey.GEMINI.envVar to "from-env")

        // when
        val key = resolveKey(ApiKey.GEMINI, baked = "from-buildkonfig", env = env::get)

        // then
        assertEquals("from-env", key)
    }

    @Test
    fun `when the env var is absent - then the baked key is used`() {
        // given — a developer machine: no env, local.properties compiled in
        val env = emptyMap<String, String>()

        // when
        val key = resolveKey(ApiKey.GEMINI, baked = "from-buildkonfig", env = env::get)

        // then
        assertEquals("from-buildkonfig", key)
    }

    @Test
    fun `when the env var is blank - then it counts as absent`() {
        // given — an unset CI secret expands to an empty string, not a missing var
        val env = mapOf(ApiKey.GEMINI.envVar to "   ")

        // when
        val key = resolveKey(ApiKey.GEMINI, baked = "from-buildkonfig", env = env::get)

        // then
        assertEquals("from-buildkonfig", key)
    }

    @Test
    fun `when neither is set - then the key is empty`() {
        // given — a secretless build with no env: the provider reports a missing key later
        val env = emptyMap<String, String>()

        // when
        val key = resolveKey(ApiKey.GEMINI, baked = "", env = env::get)

        // then
        assertEquals("", key)
    }

    @Test
    fun `when resolving - then each key reads its own variable`() {
        // given
        val env = mapOf(ApiKey.GEMINI.envVar to "g", ApiKey.OPEN_ROUTER.envVar to "o")

        // when - then
        assertEquals("g", resolveKey(ApiKey.GEMINI, baked = "", env = env::get))
        assertEquals("o", resolveKey(ApiKey.OPEN_ROUTER, baked = "", env = env::get))
        assertEquals("baked-hf", resolveKey(ApiKey.HUGGING_FACE, baked = "baked-hf", env = env::get))
    }
}

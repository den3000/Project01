package ru.den.writes.code.agenticHub.features.llm.di

import kotlinx.coroutines.test.runTest
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.llm.FakeLlmScript
import ru.den.writes.code.agenticHub.features.llm.GenerationParams
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// Backtick names без `()`/`,` — иначе iOS commonTest не компилится.
class LlmTestModuleTest {

    // Один граф на весь класс: llmTestModule целиком на factory, каждый get()
    // отдаёт свежий инстанс — тесты независимы без пересборки koin на каждый.
    private val koin = koinApplication { modules(llmTestModule) }.koin

    @Test
    fun `when script queued - then LlmApi returns scripted reply and records the call`() = runTest {
        // given
        val script = koin.get<FakeLlmScript>()
        script.queueText("hello")
        val api = koin.get<LlmApi> { parametersOf(script) }

        // when
        val result = api.send(listOf(Message(Role.USER, "hi")), GenerationParams())

        // then
        assertEquals("hello", result.text)
        assertEquals(1, script.calls.size)
    }

    @Test
    fun `when script empty - then LlmApi returns a synthetic error result`() = runTest {
        // given
        val api = koin.get<LlmApi> { parametersOf(null) }

        // when
        val result = api.send(listOf(Message(Role.USER, "hi")), GenerationParams())

        // then
        assertNotNull(result.error)
        assertEquals(null, result.text)
    }
}

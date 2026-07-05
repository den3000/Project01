package ru.den.writes.code.agenticHub.features.llm

import kotlinx.coroutines.test.runTest
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.llm.di.llmTestModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Backtick names без `()`/`,` — иначе iOS commonTest не компилится.
class QueryRewriterTest {

    private val koin = koinApplication { modules(llmTestModule) }.koin

    @Test
    fun `when the model returns a rewrite - then it is returned trimmed`() = runTest {
        // given
        val script = koin.get<FakeLlmScript>()
        script.queueText("  merge request approvals count  ")
        val rewriter = ModelQueryRewriter(koin.get { parametersOf(script) })

        // when
        val actual = rewriter.rewrite("how many approvals?")

        // then
        assertEquals("merge request approvals count", actual)
    }

    @Test
    fun `when the model reply is blank - then the original query is returned`() = runTest {
        // given
        val script = koin.get<FakeLlmScript>()
        script.queueText("   ")
        val rewriter = ModelQueryRewriter(koin.get { parametersOf(script) })

        // when
        val actual = rewriter.rewrite("how many approvals?")

        // then
        assertEquals("how many approvals?", actual)
    }

    @Test
    fun `when the model errors - then the original query is returned`() = runTest {
        // given — empty script → the fake returns a synthetic error result
        val rewriter = ModelQueryRewriter(koin.get { parametersOf(null) })

        // when
        val actual = rewriter.rewrite("how many approvals?")

        // then
        assertEquals("how many approvals?", actual)
    }

    @Test
    fun `when rewriting - then the original query is sent as the user turn`() = runTest {
        // given
        val script = koin.get<FakeLlmScript>()
        script.queueText("rewritten")
        val rewriter = ModelQueryRewriter(koin.get { parametersOf(script) })

        // when
        rewriter.rewrite("how many approvals?")

        // then
        val sent = script.calls.single().messages.first { it.role == Role.USER }.text
        assertTrue("how many approvals?" in sent)
    }

    @Test
    fun `when using the identity rewriter - then the query is unchanged`() = runTest {
        // given - when
        val actual = QueryRewriter.Identity.rewrite("how many approvals?")

        // then
        assertEquals("how many approvals?", actual)
    }
}

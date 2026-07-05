package ru.den.writes.code.agenticHub.features.llm

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.koin.core.Koin
import ru.den.writes.code.agenticHub.features.llm.ollama.OllamaModel
import kotlin.time.Duration.Companion.minutes

internal const val OLLAMA_BASE = "http://localhost:11434"

// Real inference over many questions × modes blows past runTest's 60s default — this is
// wall-clock (real HTTP), not virtual time, so give it a generous ceiling.
private val LIVE_TIMEOUT = 15.minutes

/**
 * Shared harness for the llm `*LiveTest` classes (see LIVE_TESTS.md): run [block] as a
 * coroutine test with a generous timeout, but skip it (JUnit [assumeTrue]) when the local
 * Ollama at [OLLAMA_BASE] isn't reachable. EVERY llm live test needs it — either for
 * generation (`LocalOllamaApiLiveTest`) or for embeddings during retrieval (the RAG answer
 * tests always embed through Ollama, even when the generative model is a cloud provider).
 * The [koin] graph supplies the probe [HttpClient].
 */
internal fun liveOllamaTest(koin: Koin, block: suspend () -> Unit): TestResult = runTest(timeout = LIVE_TIMEOUT) {
    assumeOllamaUp(koin.get())
    block()
}

private suspend fun assumeOllamaUp(client: HttpClient) {
    val reachable = try {
        client.get("$OLLAMA_BASE/api/tags").status.isSuccess()
    } catch (_: Exception) {
        false
    }
    assumeTrue("Ollama not reachable at $OLLAMA_BASE — skipping live test", reachable)
}

/**
 * The generative Ollama tag the local-model variant hits. Overridable so a run can
 * target whatever model is pulled locally (`-Dollama.chat.model=gemma4:31b`); defaults
 * to [OllamaModel.Default]. Prerequisite for a non-skipped run: `ollama pull <this tag>`.
 */
internal fun liveChatModel(): OllamaModel =
    System.getProperty("ollama.chat.model")?.let(OllamaModel::fromId) ?: OllamaModel.Default

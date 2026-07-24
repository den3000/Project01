package ru.den.writes.code.agenticHub.features.rag

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.test.TestResult
import org.junit.Assume.assumeTrue
import org.koin.core.Koin
import ru.den.writes.code.agenticHub.testutils.runLiveTest

internal const val OLLAMA_BASE = "http://localhost:11434"

/**
 * Shared harness for the Ollama-backed `*LiveTest` classes (see LIVE_TESTS.md): run
 * [block] as a coroutine test under the shared live timeout ([runLiveTest]), but skip it
 * (JUnit [assumeTrue]) when the local Ollama at [OLLAMA_BASE] isn't reachable — so a
 * `-PliveTests` run degrades to "skipped" instead of failing when the server is down.
 * The [koin] graph supplies the probe [HttpClient].
 */
internal fun liveOllamaTest(koin: Koin, block: suspend () -> Unit): TestResult = runLiveTest {
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

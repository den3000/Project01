package ru.den.writes.code.agenticHub.testutils

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes

// Real inference is wall-clock HTTP (not virtual time) and blows past runTest's 60s
// default — one generous ceiling shared by every jvmTest live test (features:llm/rag,
// lifecycle:session). Private: callers get the timeout, not a knob to tune per test.
private val LIVE_TIMEOUT = 15.minutes

/** Run [block] as a coroutine test under the shared live-test timeout. */
fun runLiveTest(block: suspend TestScope.() -> Unit): TestResult = runTest(timeout = LIVE_TIMEOUT) {
    block()
}

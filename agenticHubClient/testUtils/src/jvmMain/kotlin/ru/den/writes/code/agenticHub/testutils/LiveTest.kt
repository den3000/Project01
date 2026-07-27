package ru.den.writes.code.agenticHub.testutils

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// Real inference is wall-clock HTTP (not virtual time) and blows past runTest's 60s
// default — one generous ceiling shared by every jvmTest live test (features:llm/rag,
// lifecycle:session). Still the default for anything that calls a model a handful of
// times: a live test that needs longer than this is usually stuck, not slow.
private val LIVE_TIMEOUT = 15.minutes

/**
 * Run [block] as a coroutine test under the shared live-test timeout.
 *
 * [timeout] is a knob only a batch stand should touch. Fifty runs of ten turns is several
 * hundred sequential calls — an hour of wall clock is the point of the measurement, not a
 * hang — and the ceiling exists to catch a stuck call, which stays true whatever the batch
 * size. Everything else takes the default and should keep taking it.
 */
fun runLiveTest(timeout: Duration = LIVE_TIMEOUT, block: suspend TestScope.() -> Unit): TestResult =
    runTest(timeout = timeout) {
        block()
    }

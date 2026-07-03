package ru.den.writes.code.agenticHub.features.lifecycle.session

import ru.den.writes.code.agenticHub.features.lifecycle.session.UiIntent
import ru.den.writes.code.agenticHub.features.lifecycle.session.intents.MergedIntentSource
import ru.den.writes.code.agenticHub.features.lifecycle.session.intents.IntentSource

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Offline tests for [MergedIntentSource] — the select-merge of the pull-based user source
 * with the push scheduler inbox. The pump runs in [backgroundScope] so runTest cancels it.
 */
class MergedIntentSourceTest {

    @Test
    fun `when the inbox has an intent and the primary is idle - then merged yields the inbox intent`() = runTest {
        // given — a primary that never produces (mimics stdin waiting for input)
        val inbox = Channel<UiIntent>(Channel.UNLIMITED)
        val merged = MergedIntentSource(idleSource(), inbox, backgroundScope)
        inbox.trySend(UiIntent.Feed("report"))

        // when - then
        assertEquals(UiIntent.Feed("report"), merged.next())
    }

    @Test
    fun `when the primary has an intent and the inbox is empty - then merged yields the primary intent`() = runTest {
        // given
        val inbox = Channel<UiIntent>(Channel.UNLIMITED)
        val merged = MergedIntentSource(scriptedSource(UiIntent.Submit("hi")), inbox, backgroundScope)

        // when - then
        assertEquals(UiIntent.Submit("hi"), merged.next())
    }

    @Test
    fun `when the primary is exhausted - then merged yields null`() = runTest {
        // given — empty primary: next() returns null immediately
        val inbox = Channel<UiIntent>(Channel.UNLIMITED)
        val merged = MergedIntentSource(scriptedSource(), inbox, backgroundScope)

        // when - then
        assertNull(merged.next())
    }

    private fun idleSource(): IntentSource = object : IntentSource {
        override suspend fun next(): UiIntent? = awaitCancellation()
    }

    private fun scriptedSource(vararg items: UiIntent): IntentSource = object : IntentSource {
        private val queue = ArrayDeque(items.toList())
        override suspend fun next(): UiIntent? = queue.removeFirstOrNull()
    }
}

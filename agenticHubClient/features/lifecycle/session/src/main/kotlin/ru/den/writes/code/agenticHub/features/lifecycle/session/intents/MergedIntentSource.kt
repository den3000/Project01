package ru.den.writes.code.agenticHub.features.lifecycle.session.intents

import ru.den.writes.code.agenticHub.features.lifecycle.session.UiIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.selects.select

/**
 * Merges a pull-based [primary] source with a push [inbox] (the background scheduler):
 * whichever has an intent first wins. The primary is pumped into a rendezvous channel on a
 * child coroutine of [scope] so it can be `select`-merged with the inbox — a raw suspend
 * `next()` can't be a select clause. [primary] is registered first, so user input takes
 * priority over scheduler bursts. Used ONLY when the scheduler is on; without it the loop
 * drives the primary source directly, byte-for-byte unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class MergedIntentSource(
    private val primary: IntentSource,
    private val inbox: ReceiveChannel<UiIntent>,
    scope: CoroutineScope,
) : IntentSource {
    private val pumped: ReceiveChannel<UiIntent> = scope.produce(capacity = Channel.RENDEZVOUS) {
        while (true) {
            val intent = primary.next() ?: break
            send(intent)
            // drive returns on Exit; stop pumping so a push source (TUI) doesn't wedge the scope
            // on the next receive and the session can actually terminate.
            if (intent == UiIntent.Exit) break
        }
    }

    override suspend fun next(): UiIntent? = select {
        pumped.onReceiveCatching { it.getOrNull() }
        inbox.onReceiveCatching { it.getOrNull() }
    }

    override fun onTurnFailed() = primary.onTurnFailed()
    override val terminated: Boolean get() = primary.terminated
}

package ru.den.writes.code.agenticHub.features.lifecycle.session.intents

import ru.den.writes.code.agenticHub.features.lifecycle.session.UiIntent
import kotlinx.coroutines.channels.Channel

/**
 * Push→pull bridge for the TUI: Kotter key handlers call [offer]; the
 * view-model loop pulls via [next]. [close] ends the loop. Unlimited buffer so
 * offers never block the render thread.
 */
public class ChannelIntentSource : IntentSource {
    private val channel = Channel<UiIntent>(Channel.UNLIMITED)

    fun offer(intent: UiIntent) {
        channel.trySend(intent)
    }

    fun close() {
        channel.close()
    }

    override suspend fun next(): UiIntent? = channel.receiveCatching().getOrNull()
}

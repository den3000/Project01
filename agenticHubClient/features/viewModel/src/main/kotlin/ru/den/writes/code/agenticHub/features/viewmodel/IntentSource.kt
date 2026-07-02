package ru.den.writes.code.agenticHub.features.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlin.time.Duration

/**
 * Pull-based source of [UiIntent]s driving [SessionViewModel.run]. The plain /
 * feed path adapts a [PromptSource]; the TUI pushes intents from Kotter key
 * handlers into a [ChannelIntentSource]. [next] returns null when the source
 * is exhausted (EOF / file consumed / channel closed).
 */
public interface IntentSource {
    suspend fun next(): UiIntent?

    /** Signal that the last turn failed — a feed source uses this to abort. */
    fun onTurnFailed() {}

    /** True if the source stopped because it aborted (e.g. a failed feed turn). */
    val terminated: Boolean get() = false
}

/**
 * Adapts a [PromptSource] into an [IntentSource]: Prompt → Submit, Command → the
 * classified [SessionCommand] itself (which is a [UiIntent]), Stop → null.
 * [throttle] is applied before each intent except the first — the feed source
 * passes 16s, interactive / TUI pass zero. (This is where the per-turn
 * `delay(16s)` feed throttle now lives.)
 */
public class PromptSourceIntents(
    private val source: PromptSource,
    private val throttle: Duration = Duration.ZERO,
) : IntentSource {
    private var first = true

    override suspend fun next(): UiIntent? {
        if (!first && throttle > Duration.ZERO) delay(throttle)
        first = false
        return when (val r = source.nextPrompt()) {
            is PromptResult.Prompt -> UiIntent.Submit(r.text)
            is PromptResult.Command -> r.command
            PromptResult.Reuse -> UiIntent.Reuse
            PromptResult.Stop -> null
        }
    }

    override fun onTurnFailed() = source.notifyTurnFailed()

    override val terminated: Boolean get() = source.terminated
}

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

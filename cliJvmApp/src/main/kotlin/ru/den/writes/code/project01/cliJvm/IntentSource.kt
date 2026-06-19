package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * Pull-based source of [UiIntent]s driving [SessionViewModel.run]. The plain /
 * feed path adapts a [PromptSource]; the TUI pushes intents from Kotter key
 * handlers into a [ChannelIntentSource]. [next] returns null when the source
 * is exhausted (EOF / file consumed / channel closed).
 */
internal interface IntentSource {
    suspend fun next(): UiIntent?

    /** True if the source stopped because it aborted (e.g. a failed feed turn). */
    val terminated: Boolean get() = false
}

/**
 * Adapts a [PromptSource] into an [IntentSource]: Prompt → Submit, Command →
 * SlashCommand, Stop → null. [throttle] is applied before each intent except
 * the first — the feed source passes 16s, interactive / TUI pass zero. (This
 * is where the old `delay(16s)` from `SessionLoop.send` now lives.)
 */
internal class PromptSourceIntents(
    private val source: PromptSource,
    private val throttle: Duration = Duration.ZERO,
) : IntentSource {
    private var first = true

    override suspend fun next(): UiIntent? {
        if (!first && throttle > Duration.ZERO) delay(throttle)
        first = false
        return when (val r = source.nextPrompt()) {
            is PromptResult.Prompt -> UiIntent.Submit(r.text)
            is PromptResult.Command -> UiIntent.SlashCommand(r.command)
            PromptResult.Stop -> null
        }
    }

    override val terminated: Boolean get() = source.terminated
}

/**
 * Push→pull bridge for the TUI: Kotter key handlers call [offer]; the
 * view-model loop pulls via [next]. [close] ends the loop. Unlimited buffer so
 * offers never block the render thread.
 */
internal class ChannelIntentSource : IntentSource {
    private val channel = Channel<UiIntent>(Channel.UNLIMITED)

    fun offer(intent: UiIntent) {
        channel.trySend(intent)
    }

    fun close() {
        channel.close()
    }

    override suspend fun next(): UiIntent? = channel.receiveCatching().getOrNull()
}

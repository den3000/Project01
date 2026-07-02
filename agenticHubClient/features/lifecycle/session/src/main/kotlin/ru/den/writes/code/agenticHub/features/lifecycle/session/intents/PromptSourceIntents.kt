package ru.den.writes.code.agenticHub.features.lifecycle.session.intents

import ru.den.writes.code.agenticHub.features.lifecycle.session.PromptResult
import ru.den.writes.code.agenticHub.features.lifecycle.session.PromptSource
import ru.den.writes.code.agenticHub.features.lifecycle.session.UiIntent
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * Adapts a [PromptSource] into an [IntentSource]: Prompt → Submit, Command → the
 * classified `SessionCommand` itself (which is a [UiIntent]), Stop → null.
 * [throttle] is applied before each intent except the first — the feed source
 * passes 16s, interactive / TUI pass zero. (This is where the per-turn
 * `delay(16s)` feed throttle lives.)
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

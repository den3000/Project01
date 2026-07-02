package ru.den.writes.code.agenticHub.features.lifecycle.session.intents

import ru.den.writes.code.agenticHub.features.lifecycle.session.UiIntent

/**
 * Pull-based source of [UiIntent]s driving `SessionViewModel.run`. The plain /
 * feed path adapts a `PromptSource`; the TUI pushes intents from Kotter key
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

package ru.den.writes.code.project01.cliJvm.plain

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import ru.den.writes.code.project01.cliJvm.IntentSource
import ru.den.writes.code.project01.cliJvm.SessionViewModel
import ru.den.writes.code.project01.cliJvm.UiLine
import ru.den.writes.code.project01.cliJvm.UiState

/**
 * Plain renderer of [UiState]: reproduces the previous output byte-for-byte —
 * reply + footer → stdout; `[session]` / `[task]` / `[warning]` / `[error]` / … →
 * stderr (the split a user relies on when redirecting a transcript). The default
 * renderer for feed / oneshot / non-TTY.
 *
 * It owns no formatting: each newly-appended [UiLine] maps to a [PlainView]
 * variant via [toPlainView] and prints once, in order. A conflating
 * `StateFlow` may skip intermediate states, so a final flush prints whatever the
 * collector missed — line order within each stream is preserved either way.
 */
internal class PlainRenderer {
    private var printed = 0

    suspend fun run(
        vm: SessionViewModel,
        primary: IntentSource,
        followUp: IntentSource? = null,
    ): Unit = coroutineScope {
        val collector = launch { vm.state.collect { flush(it) } }
        try {
            vm.run(primary, followUp)
        } finally {
            collector.cancel()
            collector.join()
            flush(vm.state.value)
        }
    }

    private fun flush(state: UiState) {
        state.lines.drop(printed).forEach { it.toPlainView().render() }
        printed = state.lines.size
    }
}

/** A transcript [UiLine] → its plain renderer. */
private fun UiLine.toPlainView(): PlainView = when (this) {
    is UiLine.User -> UserPlainView
    is UiLine.Assistant -> AssistantPlainView(reply, agent)
    is UiLine.Turn -> TurnPlainView(usage, modelId, durationMs, session)
    is UiLine.Judge -> JudgePlainView(violations)
    is UiLine.Stage -> StagePlainView(advance)
    is UiLine.Error -> ErrorPlainView(reason)
    is UiLine.Notice -> NoticePlainView(text)
}

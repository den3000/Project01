package ru.den.writes.code.project01.cliJvm.plain

import ru.den.writes.code.project01.cliJvm.StageAdvance

/** A task-stage FSM move on stderr (the `[task] …` line). */
internal data class StagePlainView(val advance: StageAdvance) : PlainView {
    override fun stderr(): List<String> = when (advance) {
        StageAdvance.None -> emptyList()
        is StageAdvance.Advanced ->
            listOf("[task] stage: ${advance.from?.keyword ?: "(none)"} → ${advance.to.keyword} (auto)")
        is StageAdvance.Rejected ->
            listOf(
                "[task] model proposed ${advance.from?.keyword ?: "(none)"} → ${advance.proposed.keyword}, " +
                    "not allowed (allowed: ${advance.allowed.joinToString(", ") { it.keyword }}) — ignored"
            )
    }
}

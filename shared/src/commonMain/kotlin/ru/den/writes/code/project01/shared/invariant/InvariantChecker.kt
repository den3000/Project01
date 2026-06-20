package ru.den.writes.code.project01.shared.invariant

import ru.den.writes.code.project01.shared.memory.RuleEntry

/**
 * Independent check of one assistant reply against the active invariants.
 *
 * A `fun interface` so the host injects an implementation the same way it
 * injects `LlmApi`: the default is [LlmInvariantJudge] (a separate LLM pass),
 * but a deterministic keyword matcher or a hybrid could satisfy the same
 * contract without touching the call site.
 *
 * [rules] are the global invariants (the `rules/` layer); [constraints] are
 * the `constraints` bullets of the profile the answering agent spoke with. The
 * checker judges the reply against both AND flags any [constraints] that
 * contradict [rules]. Implementations must be fail-open — a transport or parse
 * failure yields [InvariantVerdict.CLEAN], never a spurious block.
 */
fun interface InvariantChecker {
    suspend fun check(
        assistantReply: String,
        rules: List<RuleEntry>,
        constraints: List<String>,
    ): InvariantVerdict
}

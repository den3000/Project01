package ru.den.writes.code.project01.shared.invariant

/**
 * One invariant breach the judge found in an assistant reply.
 *
 * [ruleId] is the three-digit id of the violated `rules/` entry (see
 * `RuleEntry`) when the breach maps to a global invariant; it is null when the
 * breach is against the answering agent's profile `constraints` (which carry
 * no id) or when the judge reports a constraints↔rules conflict. [explanation]
 * is the judge's short, human-readable reason — what was proposed and which
 * invariant it crosses — surfaced verbatim in the host's banner.
 */
data class InvariantViolation(
    val ruleId: String?,
    val explanation: String,
)

/**
 * Verdict of one independent judge pass over an assistant reply.
 *
 * [passed] is true exactly when [violations] is empty — the reply honoured
 * every active invariant (global `rules` plus the answering agent's
 * `constraints`) and those constraints did not contradict the rules. A
 * non-empty list makes the host suppress the turn (see `SessionLoop`): the
 * reply is shown but not persisted, and the task stage is held.
 */
data class InvariantVerdict(
    val passed: Boolean,
    val violations: List<InvariantViolation>,
) {
    companion object {
        /** Nothing to flag — the clean pass a disabled / empty / failed judge returns. */
        val CLEAN: InvariantVerdict = InvariantVerdict(passed = true, violations = emptyList())
    }
}

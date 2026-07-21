package ru.den.writes.code.agenticHub.features.agent.invariant

/**
 * Independent check of one turn against the active invariants.
 *
 * A `fun interface` so the host injects an implementation the same way it
 * injects `LlmApi`: the default is [LlmInvariantJudge] (a separate LLM pass),
 * but a deterministic keyword matcher or a hybrid could satisfy the same
 * contract without touching the call site.
 *
 * The whole input travels as [JudgeInput] — see there for what the judge does
 * and does not get to see. The checker judges the reply against both the global
 * rules and the answering profile's constraints, AND flags any constraint that
 * contradicts a rule. Implementations must be fail-open — a transport or parse
 * failure yields [InvariantVerdict.CLEAN], never a spurious block.
 */
fun interface InvariantChecker {
    suspend fun check(input: JudgeInput): InvariantVerdict
}

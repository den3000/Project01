package ru.den.writes.code.agenticHub.features.agent.invariant

import ru.den.writes.code.agenticHub.features.memory.RuleEntry
import ru.den.writes.code.agenticHub.features.memory.TaskStage

/**
 * Everything the judge is allowed to see about one turn.
 *
 * A structure rather than a parameter list because this input grows: a judge
 * holding only the reply cannot tell a fact the user supplied from one the
 * model invented, so more of the turn keeps arriving here. As a data class each
 * addition is additive — no implementation, and no test lambda, changes arity.
 * Same shape as `ContextStrategy.onTurn(TurnContext)`.
 *
 * The fields fall into three kinds, and which kind a field belongs to decides
 * what the judge may do with it:
 *
 *  - **Binding** — [rules] and [constraints]. A violation may be reported only
 *    against these.
 *  - **Context for judgement** — [stage], [format], [style]. Never a violation
 *    on their own: they are here so the judge doesn't mistake an artefact of the
 *    requested shape for a defect. Making them binding would repeat the failure
 *    this whole design exists to fix — a reply that legitimately cited no
 *    document would be blocked by a `format` bullet about naming documents.
 *  - **Untrusted material** — [assistantReply] and [userMessage]. Written by
 *    the user or the model, fenced in the prompt, never read as instructions.
 *
 * What is deliberately NOT here matters as much: the chat history stays out, so
 * the judge isn't subject to the conversational pressure that may have produced
 * the violation it is auditing, and the profile's `context` section stays out
 * because it says how to work — a judge reading it turns advice into invariants.
 *
 * [userMessage] is the turn's prompt. Without it the judge cannot tell a fact
 * the user just supplied from one the model invented, and it blocks honest work
 * — that happened live: an assistant that correctly told an unregistered caller
 * "I could not find you in the register" was flagged for inventing the caller,
 * whose name the caller had given one message earlier.
 */
data class JudgeInput(
    val assistantReply: String,
    val userMessage: String = "",
    val rules: List<RuleEntry> = emptyList(),
    val constraints: List<String> = emptyList(),
    val format: List<String> = emptyList(),
    val style: List<String> = emptyList(),
    val stage: TaskStage? = null,
) {
    /**
     * Whether anything here can be violated at all. Evidence without an
     * invariant leaves nothing to enforce, so the judge skips the wire call —
     * and [format] / [style] / [stage] deliberately do not count, being context
     * rather than invariants.
     */
    val hasInvariants: Boolean get() = rules.isNotEmpty() || constraints.isNotEmpty()
}

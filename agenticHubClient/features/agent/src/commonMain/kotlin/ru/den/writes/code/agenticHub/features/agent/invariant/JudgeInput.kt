package ru.den.writes.code.agenticHub.features.agent.invariant

import ru.den.writes.code.agenticHub.features.memory.RuleEntry

/**
 * Everything the judge is allowed to see about one turn.
 *
 * A structure rather than a parameter list because this input grows: a judge
 * holding only the reply cannot tell a fact the user supplied from one the
 * model invented, so more of the turn keeps arriving here. As a data class each
 * addition is additive — no implementation, and no test lambda, changes arity.
 * Same shape as `ContextStrategy.onTurn(TurnContext)`.
 *
 * What is deliberately NOT here is as much part of the contract as what is: the
 * chat history stays out, so the judge isn't subject to the conversational
 * pressure that may have produced the violation it is auditing.
 *
 * [rules] are the global invariants (the `rules/` layer); [constraints] are the
 * `constraints` bullets of the profile the answering agent spoke with.
 */
data class JudgeInput(
    val assistantReply: String,
    val rules: List<RuleEntry>,
    val constraints: List<String>,
)

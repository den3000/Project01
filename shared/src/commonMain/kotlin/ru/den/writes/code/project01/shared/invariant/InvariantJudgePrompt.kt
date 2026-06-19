package ru.den.writes.code.project01.shared.invariant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import ru.den.writes.code.project01.shared.memory.RuleEntry

/**
 * Pure helpers for the invariant-judge call — kept apart from the
 * LLM-touching [LlmInvariantJudge] so prompt-building and the
 * (failure-tolerant) JSON parsing are unit-testable without a network. Same
 * split as `FactsExtractor` beside `StickyFacts`.
 */
internal object InvariantJudgePrompt {
    const val JUDGE_MAX_TOKENS: Int = 512

    /**
     * Build the single USER turn that asks the judge to audit [reply] against
     * the invariants. The judge runs as an INDEPENDENT pass: it sees only this
     * prompt — the invariants and the reply — never the chat history, so it
     * isn't pulled by the same conversational pressure that may have produced
     * a violation.
     *
     * Two checks in one verdict: (1) does [reply] violate any global [rules] or
     * the answering agent's [constraints]; (2) do those [constraints]
     * themselves contradict the [rules] (a misconfiguration, reported as a
     * violation too).
     */
    fun buildJudgePrompt(reply: String, rules: List<RuleEntry>, constraints: List<String>): String = buildString {
        appendLine("You are an INVARIANT AUDITOR. You do not help, write, or continue the work.")
        appendLine("Your only job: decide whether the assistant reply below breaks any invariant.")
        appendLine()
        appendLine("Global invariants (rules) — must never be violated:")
        if (rules.isEmpty()) {
            appendLine("(none)")
        } else {
            rules.forEach { appendLine("- [${it.id}] ${it.text.replace("\n", " ").trim()}") }
        }
        appendLine()
        appendLine("Active agent constraints (also binding for this reply):")
        if (constraints.isEmpty()) {
            appendLine("(none)")
        } else {
            constraints.forEach { appendLine("- ${it.trim()}") }
        }
        appendLine()
        appendLine("Assistant reply to audit:")
        appendLine("\"\"\"")
        appendLine(reply)
        appendLine("\"\"\"")
        appendLine()
        appendLine("Check BOTH:")
        appendLine("1. Does the reply propose or endorse anything that breaks a rule or a constraint?")
        appendLine("2. Does any constraint itself contradict a rule? (report it too)")
        appendLine()
        appendLine("Return ONLY a JSON object — no prose, no code fences:")
        appendLine("""{"passed": <true|false>, "violations": [{"ruleId": "<rule id or null>", "explanation": "<short reason>"}]}""")
        append("""If nothing is violated: {"passed": true, "violations": []}.""")
    }

    /**
     * Parse the judge's reply into an [InvariantVerdict]. Tolerant in the same
     * way as `FactsExtractor.parseObjectOrNull`: strips a ```json fence, parses
     * leniently, and degrades to [InvariantVerdict.CLEAN] on ANY failure (null
     * / blank / not an object / no `violations` array / parse error) — fail-open,
     * so a judge hiccup never blocks a turn.
     *
     * `passed` is derived as `violations.isEmpty()` — the model's own `passed`
     * field is NOT trusted. A violation needs a non-blank `explanation`, while
     * `ruleId` is optional (null for constraint breaches and conflicts).
     */
    fun parseVerdict(replyText: String?): InvariantVerdict {
        val obj = replyText?.let(::parseObjectOrNull) ?: return InvariantVerdict.CLEAN
        val array = obj["violations"] as? JsonArray ?: return InvariantVerdict.CLEAN
        val violations = array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val explanation = (item["explanation"] as? JsonPrimitive)?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val ruleId = (item["ruleId"] as? JsonPrimitive)?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            InvariantViolation(ruleId = ruleId, explanation = explanation)
        }
        return InvariantVerdict(passed = violations.isEmpty(), violations = violations)
    }

    private fun parseObjectOrNull(raw: String): JsonObject? {
        val text = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```")
            .trim()
        return try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }
}

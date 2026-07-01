package ru.den.writes.code.project01.shared.invariant

import ru.den.writes.code.project01.shared.llm.GenerationParams
import ru.den.writes.code.project01.shared.llm.LlmApi
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.memory.RuleEntry
import ru.den.writes.code.project01.shared.util.logWarn

/**
 * Default [InvariantChecker]: judges a reply with a SEPARATE LLM call.
 *
 * The pass is deliberately context-free — it sends only the judge prompt
 * (invariants + the reply under audit), never the chat history or the memory
 * layer — so the judge isn't subject to the same conversational pressure that
 * may have produced the violation. Thin by design (mirrors `AgentResponder`):
 * the prompt and verdict parsing live in [InvariantJudgePrompt]; this class
 * only does the wire call and stays fail-open.
 *
 * [params] is fixed by the host (NOT the user's knobs): low temperature for a
 * stable verdict, and thinking DISABLED (thinkingBudget = 0) so reasoning can't
 * eat the output budget and truncate the JSON mid-verdict — the whole budget
 * goes to the answer. (A thinking judge would burn tokens and, worse, truncate
 * exactly when it finds a violation, since that's when it reasons most.)
 */
class LlmInvariantJudge(
    private val llmApi: LlmApi,
    private val params: GenerationParams = GenerationParams(
        maxTokens = InvariantJudgePrompt.JUDGE_MAX_TOKENS,
        temperature = 0.0,
        thinkingBudget = 0,
    ),
) : InvariantChecker {
    override suspend fun check(
        assistantReply: String,
        rules: List<RuleEntry>,
        constraints: List<String>,
    ): InvariantVerdict {
        // Nothing to enforce → no wire call, no overhead tokens.
        if (rules.isEmpty() && constraints.isEmpty()) return InvariantVerdict.CLEAN
        val result = llmApi.send(
            messages = listOf(
                Message(Role.USER, InvariantJudgePrompt.buildJudgePrompt(assistantReply, rules, constraints)),
            ),
            params = params,
        )
        // Diagnostics: surface the judge's raw verdict (the request-header dump
        // already shows the judge prompt; this shows its answer) so a
        // "why didn't it fire?" is inspectable without a debugger.
        logWarn("[invariant-judge] verdict: ${result.text ?: "(no text; error=${result.error})"}")
        val parsed = InvariantJudgePrompt.parseVerdictOrNull(result.text)
        // Fail-open: a transport error OR an unparseable (non-JSON) verdict both
        // degrade to CLEAN. But a non-blank reply that didn't parse is the
        // silent-masking case — warn loudly instead of hiding it.
        if (parsed == null && !result.text.isNullOrBlank()) {
            logWarn("[invariant-judge] verdict was not JSON → fail-open (treated as passed); tighten the prompt/model")
        }
        return parsed ?: InvariantVerdict.CLEAN
    }
}

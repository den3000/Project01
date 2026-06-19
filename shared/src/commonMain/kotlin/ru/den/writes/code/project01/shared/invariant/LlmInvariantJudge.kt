package ru.den.writes.code.project01.shared.invariant

import ru.den.writes.code.project01.shared.llm.GenerationParams
import ru.den.writes.code.project01.shared.llm.LlmApi
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.memory.RuleEntry

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
 * [params] is fixed by the host to a low-temperature, short-output budget so
 * the verdict is stable — it is NOT the user's generation knobs.
 */
class LlmInvariantJudge(
    private val llmApi: LlmApi,
    private val params: GenerationParams = GenerationParams(
        maxTokens = InvariantJudgePrompt.JUDGE_MAX_TOKENS,
        temperature = 0.0,
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
        // Fail-open: a transport error (text == null) parses to CLEAN, so a
        // judge outage degrades to "not blocked" rather than killing the turn.
        return InvariantJudgePrompt.parseVerdict(result.text)
    }
}

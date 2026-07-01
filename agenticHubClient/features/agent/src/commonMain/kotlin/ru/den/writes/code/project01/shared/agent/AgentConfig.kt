package ru.den.writes.code.project01.shared.agent

import ru.den.writes.code.project01.shared.llm.GenerationParams
import ru.den.writes.code.project01.shared.llm.LlmApi
import ru.den.writes.code.project01.shared.llm.ToolExecutor

/**
 * Static description of a single agent: the model surface it talks to
 * ([llmApi], which already carries the bound model, transport and
 * credentials), the generation knobs it applies on every turn ([params]),
 * and the fixed memory profile it speaks with ([profileName]).
 *
 * [profileName] names a stored profile this agent always uses. `null` means
 * "not pinned — fall back to the session's live active profile", which is
 * the single-agent default so REPL `/profile-use` keeps working. Rules and
 * the current task are NOT held here: they are shared across agents and read
 * fresh each turn, so the host composes the per-turn memory layer (this
 * agent's profile + the shared rules + task) and passes it into
 * [AgentResponder.respond] — the responder itself never reads [profileName].
 */
data class AgentConfig(
    val llmApi: LlmApi,
    val params: GenerationParams,
    val profileName: String? = null,
    /**
     * Backs the function-calling loop in [AgentResponder]: when non-null and
     * the model returns tool calls, the responder runs each through this and
     * feeds the result back. null (default) = no tool execution — the responder
     * makes a single [LlmApi.send] call exactly as before.
     */
    val toolExecutor: ToolExecutor? = null,
)

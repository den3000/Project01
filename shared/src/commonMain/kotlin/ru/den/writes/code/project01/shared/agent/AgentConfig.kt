package ru.den.writes.code.project01.shared.agent

import ru.den.writes.code.project01.shared.llm.GenerationParams
import ru.den.writes.code.project01.shared.llm.LlmApi

/**
 * Static description of a single agent: the model surface it talks to
 * ([llmApi], which already carries the bound model, transport and
 * credentials) and the generation knobs it applies on every turn
 * ([params]).
 *
 * Deliberately holds no memory (profile / rules / task). The memory layer
 * is read fresh each turn and is mutable at runtime (REPL `/task`,
 * `/memory-mode`, `/profile-use`), so freezing a snapshot here would
 * desync the wire from the live state — the host passes the per-turn
 * memory layer into [AgentResponder.respond] instead. Per-agent memory and
 * a task-stage binding attach here only once a consumer reads them.
 */
data class AgentConfig(
    val llmApi: LlmApi,
    val params: GenerationParams,
)

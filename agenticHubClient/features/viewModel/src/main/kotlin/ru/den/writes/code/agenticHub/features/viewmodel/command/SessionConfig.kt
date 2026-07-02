package ru.den.writes.code.agenticHub.features.viewmodel.command

import ru.den.writes.code.agenticHub.features.viewmodel.ContextStrategyKind
import ru.den.writes.code.agenticHub.features.agent.StageAgentSpec
import ru.den.writes.code.agenticHub.features.agent.StageJudgeSpec
import ru.den.writes.code.agenticHub.features.agent.memory.MemoryMode

/**
 * The session-lifetime configuration a chat starts with — everything the runtime
 * hydrates beyond the per-turn generation knobs: which session/history, context
 * strategy, memory + task/profile, per-stage agents and judges, the file-feed,
 * MCP servers, schedules, and the TUI toggle.
 *
 * Carried as the typed payload of [StartCommand.RunChat]. Pulled out of the command
 * so the "initial config the session runs with" is one named type, distinct from
 * the command envelope ([StartCommand]) and the per-turn knobs ([StartCommand.SessionInitialState]).
 */
public data class SessionConfig(
    val session: String?,
    val feedFile: String?,
    val chunkChars: Int,
    val feedInstruction: String,
    val byLine: Boolean,
    val strategy: ContextStrategyKind,
    val keepLast: Int,
    val summarizeEvery: Int,
    val task: String?,
    val profile: String?,
    val memoryMode: MemoryMode?,
    val stageAgents: List<StageAgentSpec>,
    val tui: Boolean,
    val judgeAgents: List<StageJudgeSpec>,
    val mcpServers: List<String> = emptyList(),
    val schedules: List<ScheduleSpec> = emptyList(),
)

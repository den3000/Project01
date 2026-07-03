package ru.den.writes.code.agenticHub.features.lifecycle.session.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.features.agent.RoutedAgent
import ru.den.writes.code.agenticHub.features.agent.RoutedJudge
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.features.lifecycle.session.SessionAssembly
import ru.den.writes.code.agenticHub.features.lifecycle.session.buildSessionViewModel
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.ToolDefinition
import ru.den.writes.code.agenticHub.features.llm.ToolExecutor
import ru.den.writes.code.agenticHub.features.memory.ContextStrategy
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.db.HistoryStore

/**
 * The nine leaf dependencies [buildSessionViewModel] needs, grouped into one
 * holder. They are all runtime-resolved upstream (llmApi/history/memory/agents),
 * so this is a single [org.koin.core.parameter.parametersOf] payload — also the
 * way past Koin's 5-argument destructuring limit.
 */
public data class SessionAssemblyArgs(
    val cliArgs: StartCommand.SessionInitialState,
    val llmApi: LlmApi,
    val historyStore: HistoryStore?,
    val strategy: ContextStrategy,
    val memory: MemoryProvider?,
    val routedAgents: List<RoutedAgent>,
    val routedJudges: List<RoutedJudge>,
    val toolDefs: List<ToolDefinition> = emptyList(),
    val toolExecutor: ToolExecutor? = null,
)

/** Koin module assembling the per-session MVI stack from a [SessionAssemblyArgs]. */
public val sessionModule: Module = module {
    factory<SessionAssembly> { (a: SessionAssemblyArgs) ->
        buildSessionViewModel(
            cliArgs = a.cliArgs,
            llmApi = a.llmApi,
            historyStore = a.historyStore,
            strategy = a.strategy,
            memory = a.memory,
            routedAgents = a.routedAgents,
            routedJudges = a.routedJudges,
            toolDefs = a.toolDefs,
            toolExecutor = a.toolExecutor,
        )
    }
}
